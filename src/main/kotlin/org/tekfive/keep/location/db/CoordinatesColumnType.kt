package org.tekfive.keep.location.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.location.Coordinates

/**
 * Exposed [ColumnType] for PostGIS `GEOGRAPHY(POINT, 4326)`.
 *
 * Stores [Coordinates] as a PostGIS geography point using SRID 4326 (WGS 84).
 * Values are read/written as WKT (Well-Known Text) strings: `SRID=4326;POINT(lon lat)`.
 *
 * Requires the PostGIS extension: `CREATE EXTENSION IF NOT EXISTS postgis`.
 */
class CoordinatesColumnType : ColumnType<Coordinates>() {

    override fun sqlType(): String = "GEOGRAPHY(POINT, 4326)"

    override fun valueFromDB(value: Any): Coordinates {
        val text = value.toString()
        return if (WKB_HEX_REGEX.matches(text)) parseWkbHex(text) else parsePoint(text)
    }

    override fun notNullValueToDB(value: Coordinates): Any {
        return "SRID=4326;POINT(${value.longitude} ${value.latitude})"
    }

    override fun nonNullValueToString(value: Coordinates): String {
        return "'SRID=4326;POINT(${value.longitude} ${value.latitude})'::geography"
    }

    override fun parameterMarker(value: Coordinates?): String = "?::geography"

    companion object {
        const val Extension = "postgis"

        private val POINT_REGEX = Regex("""POINT\s*\(\s*([+-]?\d+\.?\d*)\s+([+-]?\d+\.?\d*)\s*\)""", RegexOption.IGNORE_CASE)

        // Matches a hex-encoded WKB/EWKB string (all hex digits, even length, at least 42 chars for a point)
        private val WKB_HEX_REGEX = Regex("""[0-9A-Fa-f]{42,}""")

        internal fun parsePoint(text: String): Coordinates {
            val match = POINT_REGEX.find(text)
                ?: error("Cannot parse PostGIS point from: $text")
            val longitude = match.groupValues[1].toDouble()
            val latitude = match.groupValues[2].toDouble()
            return Coordinates(latitude = latitude, longitude = longitude)
        }

        /**
         * Parses an EWKB hex-encoded geography point.
         *
         * PostGIS returns geography columns as EWKB in hex when read via JDBC.
         * EWKB POINT layout (little-endian, SRID-flagged):
         *   1 byte  — byte order (01 = little-endian)
         *   4 bytes — geometry type (01000020 = Point + SRID flag, little-endian)
         *   4 bytes — SRID (E6100000 = 4326, little-endian)
         *   8 bytes — longitude as IEEE 754 double (little-endian)
         *   8 bytes — latitude  as IEEE 754 double (little-endian)
         */
        internal fun parseWkbHex(hex: String): Coordinates {
            val bytes = ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            // byte 0: byte order (01 = little-endian)
            // bytes 1-4: WKB geometry type (little-endian uint32). The SRID flag is 0x20000000.
            //   In LE, byte[4] holds the most significant byte: flag is 0x20 in that byte.
            // bytes 5-8: SRID (only present when SRID flag is set)
            val hasSrid = (bytes[4].toInt() and 0x20) != 0
            val coordOffset = if (hasSrid) 9 else 5
            val longitude = java.nio.ByteBuffer.wrap(bytes, coordOffset, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).double
            val latitude = java.nio.ByteBuffer.wrap(bytes, coordOffset + 8, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).double
            return Coordinates(latitude = latitude, longitude = longitude)
        }
    }
}

/** Registers a `GEOGRAPHY(POINT, 4326)` column that maps to [Coordinates]. */
fun Table.coordinates(name: String = "coordinates"): Column<Coordinates> =
    registerColumn(name, CoordinatesColumnType())
