package org.tekfive.keep.migration.dynamic

/** An immutable database object name. Components are stored without SQL quoting. */
data class QualifiedName(val name: String, val schema: String? = null) {
    init { quoteIdentifier(name); schema?.let(::quoteIdentifier) }
    fun toSql(): String = listOfNotNull(schema, name).joinToString(".", transform = ::quoteIdentifier)
}

internal fun quoteIdentifier(value: String): String {
    require(value.isNotBlank() && '\u0000' !in value && value.toByteArray(Charsets.UTF_8).size <= 63) {
        "Invalid PostgreSQL identifier: $value"
    }
    return "\"${value.replace("\"", "\"\"")}\""
}

internal fun literal(value: String): String = "'${value.replace("'", "''")}'"

/** Trusted SQL expression, not a complete statement. Identifiers have their own representation. */
data class SqlExpression(val text: String) {
    init { validateSqlFragment(text) }
}

/** Trusted SELECT query used by a view. */
data class SqlQuery(val text: String) {
    init { validateSqlFragment(text) }
}

/** PostgreSQL type syntax, including type modifiers and arrays. */
data class PostgresType(val sql: String) {
    init { validateSqlFragment(sql) }
}

/** Trusted function source. Dollar quoting is chosen when rendered. */
data class SqlBody(val text: String) {
    init { require(text.isNotBlank() && '\u0000' !in text) }
    internal fun quoted(): String {
        var suffix = 0
        while ("\$keep_$suffix\$" in text) suffix++
        val delimiter = "\$keep_$suffix\$"
        return "$delimiter\n$text\n$delimiter"
    }
}

enum class DropBehavior { RESTRICT, CASCADE }
enum class ReferentialAction { NO_ACTION, RESTRICT, CASCADE, SET_NULL, SET_DEFAULT;
    internal fun sql() = name.replace('_', ' ')
}
enum class IdentityGeneration { ALWAYS, BY_DEFAULT }

data class SequenceOptions(
    val start: Long? = null,
    val increment: Long? = null,
    val minValue: Long? = null,
    val maxValue: Long? = null,
    val cache: Long? = null,
    val cycle: Boolean? = null,
) {
    init { require(increment != 0L); require(cache == null || cache > 0) }
    internal fun toSql() = buildString {
        start?.let { append(" START WITH $it") }
        increment?.let { append(" INCREMENT BY $it") }
        minValue?.let { append(" MINVALUE $it") }
        maxValue?.let { append(" MAXVALUE $it") }
        cache?.let { append(" CACHE $it") }
        cycle?.let { append(if (it) " CYCLE" else " NO CYCLE") }
    }
}

data class ColumnDefinition(
    val name: String,
    val type: PostgresType,
    val nullable: Boolean = true,
    val default: SqlExpression? = null,
    val primaryKey: Boolean = false,
    val unique: Boolean = false,
    val identity: IdentityGeneration? = null,
    val generated: SqlExpression? = null,
    val collation: QualifiedName? = null,
) {
    init {
        quoteIdentifier(name)
        require(listOfNotNull(default, identity, generated).size <= 1) { "A column cannot combine default, identity, and generated expressions" }
    }
    internal fun toSql(): String = buildString {
        append("${quoteIdentifier(name)} ${type.sql}")
        collation?.let { append(" COLLATE ${it.toSql()}") }
        default?.let { append(" DEFAULT ${it.text}") }
        identity?.let { append(" GENERATED ${it.name.replace('_', ' ')} AS IDENTITY") }
        generated?.let { append(" GENERATED ALWAYS AS (${it.text}) STORED") }
        if (!nullable) append(" NOT NULL")
        if (primaryKey) append(" PRIMARY KEY")
        if (unique) append(" UNIQUE")
    }
}

sealed interface ConstraintDefinition {
    val name: String?
    fun toSql(): String

    data class PrimaryKey(override val name: String?, val columns: List<String>) : ConstraintDefinition {
        override fun toSql() = prefix(name) + "PRIMARY KEY (${names(columns)})"
    }
    data class Unique(
        override val name: String?, val columns: List<String>, val nullsNotDistinct: Boolean = false,
    ) : ConstraintDefinition {
        override fun toSql() = prefix(name) + "UNIQUE${if (nullsNotDistinct) " NULLS NOT DISTINCT" else ""} (${names(columns)})"
    }
    data class ForeignKey(
        override val name: String?, val columns: List<String>, val referencedTable: QualifiedName,
        val referencedColumns: List<String>, val onDelete: ReferentialAction? = null,
        val onUpdate: ReferentialAction? = null, val deferrable: Boolean = false,
        val initiallyDeferred: Boolean = false,
    ) : ConstraintDefinition {
        init { require(columns.isNotEmpty() && columns.size == referencedColumns.size); require(!initiallyDeferred || deferrable) }
        override fun toSql() = prefix(name) + "FOREIGN KEY (${names(columns)}) REFERENCES ${referencedTable.toSql()} (${names(referencedColumns)})" +
            (onDelete?.let { " ON DELETE ${it.sql()}" } ?: "") + (onUpdate?.let { " ON UPDATE ${it.sql()}" } ?: "") +
            (if (deferrable) " DEFERRABLE" else "") + (if (initiallyDeferred) " INITIALLY DEFERRED" else "")
    }
    data class Check(override val name: String?, val expression: SqlExpression) : ConstraintDefinition {
        override fun toSql() = prefix(name) + "CHECK (${expression.text})"
    }
    data class Exclusion(
        override val name: String?, val elements: List<ExclusionElement>, val method: String = "gist",
        val predicate: SqlExpression? = null,
    ) : ConstraintDefinition {
        init { require(elements.isNotEmpty()) }
        override fun toSql() = prefix(name) + "EXCLUDE USING ${quoteIdentifier(method)} (${elements.joinToString { it.toSql() }})" +
            (predicate?.let { " WHERE (${it.text})" } ?: "")
    }
    companion object {
        private fun prefix(name: String?) = name?.let { "CONSTRAINT ${quoteIdentifier(it)} " } ?: ""
        internal fun names(names: List<String>): String {
            require(names.isNotEmpty()) { "At least one column is required" }
            return names.joinToString(transform = ::quoteIdentifier)
        }
    }
}

data class ExclusionElement(val expression: SqlExpression, val operator: String) {
    init { require(operator.matches(Regex("[+*/<>=~!@#%^&|`?\\-]+"))) }
    internal fun toSql() = "${expression.text} WITH $operator"
}

/** Index keys may contain expressions, ordering, collations, and operator classes. */
data class IndexDefinition(
    val name: QualifiedName,
    val table: QualifiedName,
    val keys: List<SqlExpression>,
    val unique: Boolean = false,
    val method: String? = null,
    val include: List<String> = emptyList(),
    val predicate: SqlExpression? = null,
    val nullsNotDistinct: Boolean = false,
) {
    init {
        require(keys.isNotEmpty())
        require(name.schema == null || name.schema == table.schema) { "An index belongs to its table's schema" }
    }
}

data class FunctionArgument(val type: PostgresType, val name: String? = null) {
    internal fun toSql() = (name?.let { "${quoteIdentifier(it)} " } ?: "") + type.sql
}

data class FunctionDefinition(
    val name: QualifiedName, val arguments: List<FunctionArgument> = emptyList(),
    val returns: PostgresType, val language: String = "plpgsql", val body: SqlBody,
)

enum class TriggerTiming { BEFORE, AFTER, INSTEAD_OF }
enum class TriggerEvent { INSERT, UPDATE, DELETE, TRUNCATE }
enum class TriggerScope { ROW, STATEMENT }
enum class TriggerEnabled { ENABLE, DISABLE, ENABLE_REPLICA, ENABLE_ALWAYS }

data class TriggerDefinition(
    val name: String, val table: QualifiedName, val function: QualifiedName,
    val timing: TriggerTiming, val events: Set<TriggerEvent>, val scope: TriggerScope = TriggerScope.ROW,
    val condition: SqlExpression? = null, val arguments: List<String> = emptyList(),
) {
    init { require(events.isNotEmpty()); require(TriggerEvent.TRUNCATE !in events || scope == TriggerScope.STATEMENT) }
}
