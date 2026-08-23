package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or

/** A PostgreSQL-specific object owned by a [KeepSchema]. */
sealed interface PostgresSchemaObject {
    val name: String
    val table: Table

    /** Complete SQL required to create this object after its table exists. */
    fun createStatements(context: PostgresRenderContext): List<String>
}

/** Rendering context for connection-free PostgreSQL schema objects. */
data class PostgresRenderContext(
    val schemaName: String,
    val targetVersion: PostgresTargetVersion = PostgresTargetVersion(),
) {
    fun identifier(name: String): String = "\"${name.trimIdentifierQuotes().replace("\"", "\"\"")}\""

    fun qualified(name: String): String = "${identifier(schemaName)}.${identifier(name.substringAfterLast('.'))}"

    fun tableName(table: Table): String = qualified(table.tableName.substringAfterLast('.'))
}

/** PostgreSQL trigger timing. */
enum class PostgresTriggerTiming {
    BEFORE,
    AFTER,
}

/** Row-level PostgreSQL trigger events supported by the KEEP trigger-policy DSL. */
enum class PostgresTriggerEvent {
    INSERT,
    UPDATE,
    DELETE,
}

/** A real PostgreSQL UNIQUE constraint, rather than a unique index. */
class PostgresUniqueConstraintDefinition internal constructor(
    override val name: String,
    override val table: Table,
    val columns: List<Column<*>>,
) : PostgresSchemaObject {
    override fun createStatements(context: PostgresRenderContext): List<String> = listOf(
        "ALTER TABLE ${context.tableName(table)} ADD CONSTRAINT ${context.identifier(name)} " +
            "UNIQUE (${columns.joinToString { context.identifier(it.name) }})"
    )
}

/** A PostgreSQL row trigger and its generated PL/pgSQL trigger function. */
class PostgresRowTriggerDefinition internal constructor(
    override val name: String,
    override val table: Table,
    val functionName: String,
    val timing: PostgresTriggerTiming,
    val events: Set<PostgresTriggerEvent>,
    internal val handlers: Map<PostgresTriggerEvent, List<PostgresTriggerStatement>>,
) : PostgresSchemaObject {

    override fun createStatements(context: PostgresRenderContext): List<String> = listOf(
        createFunctionStatement(context),
        createTriggerStatement(context),
    )

    fun createFunctionStatement(context: PostgresRenderContext): String = buildString {
        val body = functionBody(context)
        val quote = body.availableDollarQuote()
        appendLine("CREATE OR REPLACE FUNCTION ${context.qualified(functionName)}()")
        appendLine("RETURNS trigger")
        appendLine("LANGUAGE plpgsql")
        appendLine("AS $quote")
        appendLine(body)
        append(quote)
    }

    fun createTriggerStatement(context: PostgresRenderContext): String =
        "CREATE TRIGGER ${context.identifier(name)} $timing " +
            events.joinToString(" OR ") { it.name } +
            " ON ${context.tableName(table)} FOR EACH ROW " +
            "EXECUTE FUNCTION ${context.qualified(functionName)}()"

    fun functionBody(context: PostgresRenderContext): String = buildString {
        appendLine("BEGIN")
        events.forEach { event ->
            appendLine("    IF TG_OP = '${event.name}' THEN")
            handlers[event].orEmpty().forEach { statement ->
                statement.render(context).lineSequence().forEach { line ->
                    append("        ").appendLine(line)
                }
            }
            appendLine("        RETURN ${if (event == PostgresTriggerEvent.DELETE) "OLD" else "NEW"};")
            appendLine("    END IF;")
        }
        appendLine("    RETURN NULL;")
        append("END;")
    }
}

/** Creates PostgreSQL objects bound to this table, validating every referenced column. */
fun Table.postgresObjects(block: PostgresTableObjectsBuilder.() -> Unit): List<PostgresSchemaObject> =
    PostgresTableObjectsBuilder(this).apply(block).build()

class PostgresTableObjectsBuilder internal constructor(
    private val table: Table,
) {
    private val objects = mutableListOf<PostgresSchemaObject>()

    fun uniqueConstraint(name: String, vararg columns: Column<*>) {
        validateName(name, "constraint")
        require(columns.isNotEmpty()) { "PostgreSQL UNIQUE constraint $name requires at least one column" }
        validateColumns(columns.asList())
        require(columns.toSet().size == columns.size) { "UNIQUE constraint $name contains duplicate columns" }
        objects += PostgresUniqueConstraintDefinition(name, table, columns.asList())
    }

    fun rowTrigger(
        name: String,
        functionName: String = "${table.unqualifiedName()}_${name}_fn",
        block: PostgresRowTriggerBuilder.() -> Unit,
    ) {
        validateName(name, "trigger")
        validateName(functionName, "trigger function")
        objects += PostgresRowTriggerBuilder(table, name, functionName).apply(block).build()
    }

    internal fun build(): List<PostgresSchemaObject> {
        val duplicateNames = objects.groupingBy { it.name.lowercase() }.eachCount().filterValues { it > 1 }.keys
        require(duplicateNames.isEmpty()) { "Duplicate PostgreSQL object names on ${table.tableName}: $duplicateNames" }
        return objects.toList()
    }

    private fun validateColumns(columns: List<Column<*>>) {
        require(columns.all { it.table == table }) {
            "PostgreSQL objects on ${table.tableName} may only reference columns owned by that table"
        }
    }
}

class PostgresRowTriggerBuilder internal constructor(
    private val table: Table,
    private val name: String,
    private val functionName: String,
) {
    private var timing: PostgresTriggerTiming? = null
    private val declaredEvents = linkedSetOf<PostgresTriggerEvent>()
    private val handlers = linkedMapOf<PostgresTriggerEvent, MutableList<PostgresTriggerStatement>>()

    fun before(vararg events: PostgresTriggerEvent) = timing(PostgresTriggerTiming.BEFORE, events)

    fun after(vararg events: PostgresTriggerEvent) = timing(PostgresTriggerTiming.AFTER, events)

    fun onInsert(block: PostgresInsertTriggerScope.() -> Unit) {
        handler(PostgresTriggerEvent.INSERT, PostgresInsertTriggerScope(table), block)
    }

    fun onUpdate(block: PostgresUpdateTriggerScope.() -> Unit) {
        handler(PostgresTriggerEvent.UPDATE, PostgresUpdateTriggerScope(table), block)
    }

    fun onDelete(block: PostgresDeleteTriggerScope.() -> Unit) {
        handler(PostgresTriggerEvent.DELETE, PostgresDeleteTriggerScope(table), block)
    }

    internal fun build(): PostgresRowTriggerDefinition {
        val resolvedTiming = requireNotNull(timing) { "Row trigger $name must declare before(...) or after(...)" }
        require(declaredEvents.isNotEmpty()) { "Row trigger $name must declare at least one event" }
        val missingHandlers = declaredEvents - handlers.keys
        require(missingHandlers.isEmpty()) { "Row trigger $name has no handler for events: $missingHandlers" }
        val undeclaredHandlers = handlers.keys - declaredEvents
        require(undeclaredHandlers.isEmpty()) { "Row trigger $name has undeclared event handlers: $undeclaredHandlers" }
        return PostgresRowTriggerDefinition(
            name = name,
            table = table,
            functionName = functionName,
            timing = resolvedTiming,
            events = declaredEvents.toSet(),
            handlers = handlers.mapValues { it.value.toList() },
        )
    }

    private fun timing(value: PostgresTriggerTiming, events: Array<out PostgresTriggerEvent>) {
        require(timing == null || timing == value) { "Row trigger $name cannot mix BEFORE and AFTER timing" }
        require(events.isNotEmpty()) { "Row trigger $name must declare at least one event" }
        timing = value
        declaredEvents += events
    }

    private fun <S : PostgresTriggerScope> handler(
        event: PostgresTriggerEvent,
        scope: S,
        block: S.() -> Unit,
    ) {
        require(event !in handlers) { "Row trigger $name declares more than one $event handler" }
        scope.block()
        handlers[event] = scope.statements
    }
}

sealed class PostgresTriggerScope protected constructor(
    protected val table: Table,
) {
    internal val statements = mutableListOf<PostgresTriggerStatement>()

    fun require(condition: Op<Boolean>, message: () -> String) {
        require(condition, message())
    }

    fun require(condition: Op<Boolean>, message: String) {
        require(message.isNotBlank()) { "Trigger exception message must not be blank" }
        statements += PostgresTriggerStatement.Require(condition, message)
    }

    /** Explicit PL/pgSQL escape hatch for behavior not represented by the typed policy DSL. */
    fun sql(sql: String) {
        require(sql.isNotBlank()) { "Raw trigger SQL must not be blank" }
        statements += PostgresTriggerStatement.Raw(sql.trim())
    }
}

class PostgresInsertTriggerScope internal constructor(table: Table) : PostgresTriggerScope(table) {
    val new = PostgresTriggerRow(table, "NEW")
}

class PostgresDeleteTriggerScope internal constructor(table: Table) : PostgresTriggerScope(table) {
    val old = PostgresTriggerRow(table, "OLD")
}

class PostgresUpdateTriggerScope internal constructor(table: Table) : PostgresTriggerScope(table) {
    val old = PostgresTriggerRow(table, "OLD")
    val new = PostgresTriggerRow(table, "NEW")

    fun <T : Any> transition(
        column: Column<T>,
        block: PostgresTransitionBuilder<T>.() -> Unit,
    ) {
        validateColumn(column)
        val transition = PostgresTransitionBuilder<T>().apply(block)
        transition.rules().forEach { rule ->
            val valid = (old[column] neq rule.from) or (new[column] inList rule.allowed)
            statements += PostgresTriggerStatement.Require(valid, rule.message!!)
        }
    }

    fun immutableWhen(
        condition: Op<Boolean>,
        vararg columns: Column<*>,
        message: String,
    ) {
        require(columns.isNotEmpty()) { "immutableWhen requires at least one column" }
        require(message.isNotBlank()) { "immutableWhen exception message must not be blank" }
        columns.forEach(::validateColumn)
        require(columns.toSet().size == columns.size) { "immutableWhen contains duplicate columns" }
        statements += PostgresTriggerStatement.ImmutableWhen(condition, columns.asList(), message)
    }

    private fun validateColumn(column: Column<*>) {
        require(column.table == table) {
            "Trigger on ${table.tableName} cannot reference ${column.table.tableName}.${column.name}"
        }
    }
}

class PostgresTransitionBuilder<T : Any> internal constructor() {
    private val rules = mutableListOf<PostgresTransitionRule<T>>()

    fun from(value: T): PostgresAllowedTransition<T> {
        val rule = PostgresTransitionRule<T>(value)
        rules += rule
        return PostgresAllowedTransition(rule)
    }

    internal fun rules(): List<PostgresTransitionRule<T>> {
        require(rules.isNotEmpty()) { "transition must declare at least one from(...) rule" }
        require(rules.map { it.from }.toSet().size == rules.size) { "transition contains duplicate from(...) values" }
        rules.forEach { rule ->
            require(rule.allowed.isNotEmpty()) { "Transition from ${rule.from} must allow at least one value" }
            require(rule.allowed.toSet().size == rule.allowed.size) {
                "Transition from ${rule.from} contains duplicate allowed values"
            }
            requireNotNull(rule.message) { "Transition from ${rule.from} must provide otherwise(message)" }
        }
        return rules
    }
}

class PostgresAllowedTransition<T : Any> internal constructor(
    private val rule: PostgresTransitionRule<T>,
) {
    fun allows(vararg values: T): PostgresAllowedTransition<T> {
        require(values.isNotEmpty()) { "allows requires at least one value" }
        require(rule.message == null) { "allows cannot be changed after otherwise(message)" }
        rule.allowed += values
        return this
    }

    fun otherwise(message: String) {
        require(message.isNotBlank()) { "Transition error message must not be blank" }
        require(rule.message == null) { "otherwise(message) may only be specified once" }
        rule.message = message
    }
}

internal class PostgresTransitionRule<T : Any>(
    val from: T,
    val allowed: MutableList<T> = mutableListOf(),
    var message: String? = null,
)

class PostgresTriggerRow internal constructor(
    private val table: Table,
    private val alias: String,
) {
    operator fun <T> get(column: Column<T>): ExpressionWithColumnType<T> {
        require(column.table == table) {
            "$alias row for ${table.tableName} cannot reference ${column.table.tableName}.${column.name}"
        }
        return PostgresTriggerColumn(alias, column)
    }
}

private class PostgresTriggerColumn<T>(
    private val alias: String,
    private val column: Column<T>,
) : ExpressionWithColumnType<T>() {
    override val columnType = column.columnType

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append(alias)
        queryBuilder.append('.')
        queryBuilder.append("\"${column.name.replace("\"", "\"\"")}\"")
    }
}

internal sealed interface PostgresTriggerStatement {
    fun render(context: PostgresRenderContext): String

    data class Require(
        val condition: Op<Boolean>,
        val message: String,
    ) : PostgresTriggerStatement {
        override fun render(context: PostgresRenderContext): String =
            """
            IF (${condition.renderSql()}) IS NOT TRUE THEN
                RAISE EXCEPTION USING MESSAGE = ${message.postgresStringLiteral()};
            END IF;
            """.trimIndent()
    }

    data class ImmutableWhen(
        val condition: Op<Boolean>,
        val columns: List<Column<*>>,
        val message: String,
    ) : PostgresTriggerStatement {
        override fun render(context: PostgresRenderContext): String {
            val newRow = columns.joinToString { "NEW.${context.identifier(it.name)}" }
            val oldRow = columns.joinToString { "OLD.${context.identifier(it.name)}" }
            return """
                IF (${condition.renderSql()}) AND
                   ROW($newRow) IS DISTINCT FROM ROW($oldRow) THEN
                    RAISE EXCEPTION USING MESSAGE = ${message.postgresStringLiteral()};
                END IF;
            """.trimIndent()
        }
    }

    data class Raw(val sql: String) : PostgresTriggerStatement {
        override fun render(context: PostgresRenderContext): String = sql
    }
}

private fun Op<Boolean>.renderSql(): String = QueryBuilder(prepared = false).also { it.append(this) }.toString()

private fun String.postgresStringLiteral(): String = "'${replace("'", "''")}'"

private fun String.availableDollarQuote(): String {
    var suffix = ""
    var quote = "${'$'}keep${suffix}${'$'}"
    var counter = 0
    while (quote in this) {
        counter++
        suffix = "_$counter"
        quote = "${'$'}keep${suffix}${'$'}"
    }
    return quote
}

private fun Table.unqualifiedName(): String = tableName.substringAfterLast('.').trimIdentifierQuotes()

private fun String.trimIdentifierQuotes(): String = removeSurrounding("\"")

private fun validateName(name: String, kind: String) {
    require(name.isNotBlank()) { "PostgreSQL $kind name must not be blank" }
    require(name.toByteArray(Charsets.UTF_8).size <= 63) { "PostgreSQL $kind name exceeds 63 UTF-8 bytes: $name" }
    require('\u0000' !in name) { "PostgreSQL $kind name must not contain a NUL character" }
}
