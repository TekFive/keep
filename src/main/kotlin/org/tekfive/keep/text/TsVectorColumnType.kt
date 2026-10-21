package org.tekfive.keep.text

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table

/** PostgreSQL's serialized lexemes/positions, not unprocessed document text. Use [toTsVector] to parse text. */
data class TsVector(val value: String) {
    override fun toString(): String = value
}

/** PostgreSQL's serialized query syntax. Use [plainToTsQuery] or [webSearchToTsQuery] for user input. */
data class TsQuery(val value: String) {
    override fun toString(): String = value
}

/** Native PostgreSQL TSVECTOR storage. No extension is required. */
class TsVectorColumnType : ColumnType<TsVector>() {
    override fun sqlType(): String = "TSVECTOR"
    override fun parameterMarker(value: TsVector?): String = "?::tsvector"
    override fun valueFromDB(value: Any): TsVector = if (value is TsVector) value else TsVector(value.toString())
    override fun notNullValueToDB(value: TsVector): Any = value.value
    override fun nonNullValueToString(value: TsVector): String = "${textSearchLiteral(value.value)}::tsvector"
}

/** Native PostgreSQL TSQUERY representation, also used for query parameters and expression results. */
class TsQueryColumnType : ColumnType<TsQuery>() {
    override fun sqlType(): String = "TSQUERY"
    override fun parameterMarker(value: TsQuery?): String = "?::tsquery"
    override fun valueFromDB(value: Any): TsQuery = if (value is TsQuery) value else TsQuery(value.toString())
    override fun notNullValueToDB(value: TsQuery): Any = value.value
    override fun nonNullValueToString(value: TsQuery): String = "${textSearchLiteral(value.value)}::tsquery"
}

// Use PostgreSQL's ordinary literal form when possible, and escape strings for backslashes.
private fun textSearchLiteral(value: String): String = if ('\\' in value) {
    "E'${value.replace("\\", "\\\\").replace("'", "''")}'"
} else {
    "'${value.replace("'", "''")}'"
}

/** Registers a native TSVECTOR column. Populate it with [toTsVector] or a serialized [TsVector]. */
fun Table.tsVector(name: String): Column<TsVector> = registerColumn(name, TsVectorColumnType())

/** Registers a native TSQUERY column for applications that persist search queries. */
fun Table.tsQuery(name: String): Column<TsQuery> = registerColumn(name, TsQueryColumnType())
