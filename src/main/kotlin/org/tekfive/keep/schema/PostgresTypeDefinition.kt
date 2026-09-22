package org.tekfive.keep.schema

/** Desired schema-owned PostgreSQL type; migration operations are derived from its definition. */
sealed interface PostgresTypeDefinition {
    val name: String
    fun createStatements(context: PostgresRenderContext): List<String>
}

data class PostgresEnumDefinition(override val name: String, val values: List<String>) : PostgresTypeDefinition {
    init {
        require(name.isNotBlank() && '.' !in name && '\u0000' !in name && name.toByteArray().size <= 63)
        require(values.distinct().size == values.size) { "Enum $name contains duplicate labels" }
        require(values.all { '\u0000' !in it && it.toByteArray().size <= 63 }) { "Enum labels must fit PostgreSQL's 63-byte limit" }
    }
    override fun createStatements(context: PostgresRenderContext): List<String> = listOf(
        "CREATE TYPE ${context.qualified(name)} AS ENUM (${values.joinToString { "'${it.replace("'", "''")}'" }})"
    )
}
