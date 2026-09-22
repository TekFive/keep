package org.tekfive.keep.migration

import org.jetbrains.exposed.v1.jdbc.Database
import org.tekfive.keep.schema.KeepSchema
import java.nio.file.Path
import java.util.Locale

/** Compatibility facade. New code should use migration.dynamic.PostgresMigrationGenerator. */
@Deprecated("Use org.tekfive.keep.migration.dynamic.PostgresMigrationGenerator for typed plans")
object PostgresMigrationGenerator {
    fun plan(database: Database, keepSchema: KeepSchema, nonDestructive: Boolean): PostgresMigrationPlan =
        legacy(org.tekfive.keep.migration.dynamic.PostgresMigrationGenerator.plan(database, keepSchema, nonDestructive))

    fun plan(keepSchema: KeepSchema, nonDestructive: Boolean): PostgresMigrationPlan =
        legacy(org.tekfive.keep.migration.dynamic.PostgresMigrationGenerator.plan(keepSchema, nonDestructive))

    fun generate(database: Database, keepSchema: KeepSchema, output: Path, nonDestructive: Boolean, overwrite: Boolean = false): PostgresMigrationPlan =
        plan(database, keepSchema, nonDestructive).also { it.writeTo(output, overwrite) }

    fun generate(keepSchema: KeepSchema, output: Path, nonDestructive: Boolean, overwrite: Boolean = false): PostgresMigrationPlan =
        plan(keepSchema, nonDestructive).also { it.writeTo(output, overwrite) }

    private fun legacy(plan: org.tekfive.keep.migration.dynamic.PostgresMigrationPlan) = PostgresMigrationPlan(
        plan.sqlStatements,
        plan.suppressedStatements.map { SuppressedPostgresMigrationStatement(it.sql, DestructivePostgresMigrationChange.valueOf(it.reason.name)) },
    )
}

internal fun destructiveChangeFor(sql: String): DestructivePostgresMigrationChange? {
    val normalized = sql.trimStart().replace(Regex("\\s+"), " ").uppercase(Locale.ROOT)
    return when {
        normalized.startsWith("DROP FOREIGN TABLE ") || normalized.startsWith("DROP TABLE ") ->
            DestructivePostgresMigrationChange.DROP_TABLE
        normalized.startsWith("DROP MATERIALIZED VIEW ") ->
            DestructivePostgresMigrationChange.DROP_MATERIALIZED_VIEW
        normalized.startsWith("DROP VIEW ") -> DestructivePostgresMigrationChange.DROP_VIEW
        normalized.startsWith("DROP SEQUENCE ") -> DestructivePostgresMigrationChange.DROP_SEQUENCE
        normalized.startsWith("DROP SCHEMA ") -> DestructivePostgresMigrationChange.DROP_SCHEMA
        normalized.startsWith("DROP TYPE ") -> DestructivePostgresMigrationChange.DROP_TYPE
        normalized.startsWith("DROP EXTENSION ") -> DestructivePostgresMigrationChange.DROP_EXTENSION
        DROP_COLUMN.containsMatchIn(normalized) -> DestructivePostgresMigrationChange.DROP_COLUMN
        ALTER_COLUMN_TYPE.containsMatchIn(normalized) -> DestructivePostgresMigrationChange.ALTER_COLUMN_TYPE
        DELETE_DATA.containsMatchIn(normalized) -> DestructivePostgresMigrationChange.DELETE_DATA
        normalized.startsWith("TRUNCATE ") -> DestructivePostgresMigrationChange.TRUNCATE_DATA
        else -> null
    }
}

/** Splits Exposed's comma-joined PostgreSQL ALTER COLUMN clauses so each can be safety-filtered independently. */
internal fun expandPostgresAlterTableStatement(sql: String): List<String> {
    val alterColumn = ALTER_COLUMN.find(sql) ?: return listOf(sql)
    val prefix = sql.substring(0, alterColumn.range.first).trimEnd()
    val clauses = splitTopLevelCommas(sql.substring(alterColumn.range.first).trimStart())
    return if (clauses.size == 1) listOf(sql) else clauses.map { "$prefix ${it.trim()}" }
}

private fun splitTopLevelCommas(sql: String): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    var parentheses = 0
    var singleQuoted = false
    var doubleQuoted = false
    var dollarQuote: String? = null
    var index = 0
    while (index < sql.length) {
        val activeDollarQuote = dollarQuote
        if (activeDollarQuote != null) {
            if (sql.startsWith(activeDollarQuote, index)) {
                index += activeDollarQuote.length
                dollarQuote = null
            } else {
                index++
            }
            continue
        }

        val char = sql[index]
        when {
            singleQuoted -> {
                if (char == '\'' && index + 1 < sql.length && sql[index + 1] == '\'') {
                    index += 2
                    continue
                }
                if (char == '\'') singleQuoted = false
            }
            doubleQuoted -> {
                if (char == '"' && index + 1 < sql.length && sql[index + 1] == '"') {
                    index += 2
                    continue
                }
                if (char == '"') doubleQuoted = false
            }
            char == '\'' -> singleQuoted = true
            char == '"' -> doubleQuoted = true
            char == '$' -> DOLLAR_QUOTE.find(sql, index)
                ?.takeIf { it.range.first == index }
                ?.value
                ?.let {
                    dollarQuote = it
                    index += it.length
                    continue
                }
            char == '(' -> parentheses++
            char == ')' -> if (parentheses > 0) parentheses--
            char == ',' && parentheses == 0 -> {
                parts += sql.substring(start, index)
                start = index + 1
            }
        }
        index++
    }
    parts += sql.substring(start)
    return parts
}

private val DROP_COLUMN = Regex("\\bDROP\\s+COLUMN\\b")
private val ALTER_COLUMN_TYPE = Regex("\\bALTER\\s+COLUMN\\s+[^;]+?\\s+(?:SET\\s+DATA\\s+)?TYPE\\b")
private val DELETE_DATA = Regex("(?:^|\\s)DELETE\\s+FROM\\s")
private val ALTER_COLUMN = Regex("(?i)\\bALTER\\s+COLUMN\\b")
private val DOLLAR_QUOTE = Regex("\\$[A-Za-z_][A-Za-z0-9_]*\\$|\\$\\$")
