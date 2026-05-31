package org.tekfive.keep.text

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import java.sql.Clob

/**
 * Exposed [ColumnType] for PostgreSQL's `citext` (case-insensitive text) type.
 * Requires the `citext` extension to be enabled in the database (`CREATE EXTENSION IF NOT EXISTS citext`).
 *
 * @param colLength optional max length — when set, values are validated before update.
 */
class CitextColumnType(val colLength: Int = Int.MAX_VALUE) : ColumnType<String>() {

    override fun sqlType(): String = "CITEXT"

    override fun valueFromDB(value: Any): String = when (value) {
        is String -> value
        is Clob -> value.characterStream.readText()
        else -> value.toString()
    }

    override fun nonNullValueToString(value: String): String = buildString {
        append('\'')
        append(value.replace("'", "''"))
        append('\'')
    }

    override fun validateValueBeforeUpdate(value: String?) {
        if (value != null && colLength != Int.MAX_VALUE) {
            require(value.length <= colLength) {
                "Value '$value' exceeds max length of $colLength for CITEXT column"
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CitextColumnType && colLength == other.colLength

    override fun hashCode(): Int = colLength.hashCode()

    companion object {
        const val Extension = "citext"
    }
}

/** Registers a CITEXT column for case-insensitive string storage (PostgreSQL). */
fun Table.citext(name: String, length: Int = Int.MAX_VALUE): Column<String> =
    registerColumn(name, CitextColumnType(length))
