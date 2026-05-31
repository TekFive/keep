package org.tekfive.keep.location

import org.tekfive.keep.location.db.CoordinatesColumnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoordinatesColumnTypeTest {

    private val columnType = CoordinatesColumnType()

    @Test
    fun `sqlType returns GEOGRAPHY POINT`() {
        assertEquals("GEOGRAPHY(POINT, 4326)", columnType.sqlType())
    }

    @Test
    fun `notNullValueToDB formats as SRID WKT`() {
        val coords = Coordinates(40.7128, -74.0060)
        val result = columnType.notNullValueToDB(coords)
        assertEquals("SRID=4326;POINT(-74.006 40.7128)", result)
    }

    @Test
    fun `valueFromDB parses WKT point string`() {
        val result = columnType.valueFromDB("POINT(-74.006 40.7128)")
        assertEquals(40.7128, result.latitude)
        assertEquals(-74.006, result.longitude)
    }

    @Test
    fun `parsePoint handles POINT with spaces`() {
        val result = CoordinatesColumnType.parsePoint("POINT( -74.006  40.7128 )")
        assertEquals(40.7128, result.latitude)
        assertEquals(-74.006, result.longitude)
    }

    @Test
    fun `parsePoint handles case insensitive`() {
        val result = CoordinatesColumnType.parsePoint("point(-74.006 40.7128)")
        assertEquals(40.7128, result.latitude)
        assertEquals(-74.006, result.longitude)
    }

    @Test
    fun `parsePoint throws for invalid input`() {
        assertFailsWith<IllegalStateException> {
            CoordinatesColumnType.parsePoint("not a point")
        }
    }

    @Test
    fun `nonNullValueToString formats as geography cast`() {
        val coords = Coordinates(40.7128, -74.0060)
        val result = columnType.nonNullValueToString(coords)
        assertEquals("'SRID=4326;POINT(-74.006 40.7128)'::geography", result)
    }
}
