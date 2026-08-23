package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.core.Sequence
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.data.DataTableSchemaHooks
import java.nio.file.Path
import java.util.Locale

/** Generates complete PostgreSQL installation SQL from a [KeepSchema] without a database connection. */
object PostgresFreshInstallGenerator {
    fun generate(
        keepSchema: KeepSchema,
        output: Path,
        overwrite: Boolean = false,
        targetVersion: PostgresTargetVersion = PostgresTargetVersion(),
    ): PostgresFreshInstallPlan = plan(keepSchema, targetVersion).also {
        it.writeTo(output, overwrite)
    }

    fun plan(
        keepSchema: KeepSchema,
        targetVersion: PostgresTargetVersion = PostgresTargetVersion(),
    ): PostgresFreshInstallPlan {
        validate(keepSchema)

        return withOfflinePostgresDdlContext(targetVersion) {
            val statements = mutableListOf<String>()
            statements += "CREATE SCHEMA IF NOT EXISTS ${quoteIdentifier(keepSchema.schemaName)}"
            statements += "SET search_path TO ${quoteIdentifier(keepSchema.schemaName)}, public"

            keepSchema.extensions.forEach { extension ->
                statements += "CREATE EXTENSION IF NOT EXISTS ${quoteIdentifier(extension)}"
            }

            val tableHooks = keepSchema.tables.filterIsInstance<DataTableSchemaHooks>()
            statements += keepSchema.beforeTablesSql
            statements += tableHooks.flatMap { it.customTypes }

            val tableDdl = renderTables(keepSchema.tables)
            statements += renderSequences(keepSchema.sequenceDefinitions, tableDdl.sequenceStatements)
            statements += tableDdl.tableStatements
            statements += tableDdl.afterTableStatements
            statements += tableDdl.indexStatements

            statements += tableHooks.flatMap { it.customIndices }
            val postgresContext = PostgresRenderContext(keepSchema.schemaName, targetVersion)
            statements += orderedPostgresObjects(keepSchema.declaredPostgresObjects)
                .flatMap { it.createStatements(postgresContext) }
            statements += tableHooks.flatMap { it.postSchemaCreateSql }
            statements += keepSchema.afterTablesSql

            keepSchema.views.forEach { view ->
                val create = if (view.materialized) "CREATE MATERIALIZED VIEW" else "CREATE VIEW"
                statements += "$create ${qualifiedName(keepSchema.schemaName, view.name)} AS " +
                    view.query.withoutTrailingSemicolon()
            }

            PostgresFreshInstallPlan(
                statements
                    .map { it.withoutTrailingSemicolon() }
                    .filter(String::isNotBlank)
                    .distinctBy(::normalizedStatement),
            )
        }
    }

    private fun renderTables(tables: List<Table>): RenderedTables {
        val sequenceStatements = mutableListOf<String>()
        val tableStatements = mutableListOf<String>()
        val afterTableStatements = mutableListOf<String>()
        val indexStatements = mutableListOf<String>()

        sortTablesByReferences(tables).forEach { table ->
            table.createStatement().forEach { sql ->
                when {
                    sql.startsWith("CREATE SEQUENCE", ignoreCase = true) -> sequenceStatements += sql
                    sql.startsWith("CREATE TABLE", ignoreCase = true) -> tableStatements += sql
                    else -> afterTableStatements += sql
                }
            }
            table.indices.flatMapTo(indexStatements) { it.createStatement() }
        }

        return RenderedTables(
            sequenceStatements.distinctBy(::normalizedStatement),
            tableStatements.distinctBy(::normalizedStatement),
            afterTableStatements.distinctBy(::normalizedStatement),
            indexStatements.distinctBy(::normalizedStatement),
        )
    }

    private fun renderDeclaredSequences(definitions: List<PostgresSequenceDefinition>): List<String> =
        definitions.map { definition ->
            Sequence(
                name = definition.name,
                startWith = definition.startWith,
                incrementBy = definition.incrementBy,
                minValue = definition.minValue,
                maxValue = definition.maxValue,
                cycle = definition.cycle,
                cache = definition.cache,
            ).createStatement().single()
        }

    private fun renderSequences(
        definitions: List<PostgresSequenceDefinition>,
        tableSequenceStatements: List<String>,
    ): List<String> {
        val tableSequences = tableSequenceStatements.associateBy(::sequenceNameFromCreate)
        val renderedDefinitions = definitions.zip(renderDeclaredSequences(definitions))
        val result = linkedMapOf<String, String>()

        renderedDefinitions.forEach { (definition, sql) ->
            val name = definition.name.substringAfterLast('.').trimIdentifierQuotes().lowercase(Locale.ROOT)
            result[name] = if (definition.hasOptions()) sql else tableSequences[name] ?: sql
        }
        tableSequences.forEach { (name, sql) -> result.putIfAbsent(name, sql) }
        return result.values.toList()
    }

    private fun validate(keepSchema: KeepSchema) {
        require(keepSchema.schemaName.isNotBlank()) { "KeepSchema schemaName must not be blank" }
        validateNames("extension", keepSchema.extensions)
        validateNames("sequence", keepSchema.sequenceDefinitions.map { it.name })
        validateNames("view", keepSchema.views.map { it.name })
        require(keepSchema.sequenceDefinitions.none { '.' in it.name }) {
            "Sequence definitions must use unqualified names; KeepSchema supplies the schema"
        }
        require(keepSchema.views.none { '.' in it.name }) {
            "View definitions must use unqualified names; KeepSchema supplies the schema"
        }
        require(keepSchema.beforeTablesSql.all(String::isNotBlank)) { "beforeTablesSql must not contain blank SQL" }
        require(keepSchema.afterTablesSql.all(String::isNotBlank)) { "afterTablesSql must not contain blank SQL" }
        require(keepSchema.views.all { it.query.isNotBlank() }) { "View queries must not be blank" }

        val postgresObjects = keepSchema.declaredPostgresObjects
        require(postgresObjects.all { it.table in keepSchema.tables }) {
            "PostgreSQL schema objects may only target tables declared by KeepSchema"
        }
        val duplicateObjects = postgresObjects
            .groupingBy { Triple(it::class, it.table, it.name.lowercase(Locale.ROOT)) }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateObjects.isEmpty()) { "KeepSchema contains duplicate PostgreSQL objects: $duplicateObjects" }
        val duplicateFunctions = postgresObjects.filterIsInstance<PostgresRowTriggerDefinition>()
            .groupingBy { it.functionName.lowercase(Locale.ROOT) }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateFunctions.isEmpty()) {
            "KeepSchema contains duplicate PostgreSQL trigger function names: $duplicateFunctions"
        }

        val duplicateSequenceNames = keepSchema.sequenceDefinitions
            .groupingBy { it.name.lowercase(Locale.ROOT) }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateSequenceNames.isEmpty()) {
            "KeepSchema must not contain duplicate sequence definitions: $duplicateSequenceNames"
        }

        val tables = keepSchema.tables.toSet()
        require(tables.size == keepSchema.tables.size) { "KeepSchema must not contain duplicate table objects" }
        keepSchema.tables.forEach { table ->
            val tableSchema = table.tableName.substringBeforeLast('.', missingDelimiterValue = "")
                .trimIdentifierQuotes()
            require(tableSchema.isEmpty() || tableSchema.equals(keepSchema.schemaName, ignoreCase = true)) {
                "Table ${table.tableName} belongs to schema $tableSchema, not KeepSchema ${keepSchema.schemaName}"
            }
            table.foreignKeys.forEach { foreignKey ->
                require(foreignKey.targetTable in tables) {
                    "Table ${table.tableName} references undeclared table ${foreignKey.targetTable.tableName}; " +
                        "a fresh-install schema must include every foreign-key target"
                }
            }
        }

        val objectNames = buildList {
            addAll(keepSchema.tables.map { it.tableName.substringAfterLast('.').trimIdentifierQuotes() })
            addAll(keepSchema.views.map { it.name.trimIdentifierQuotes() })
            addAll(keepSchema.sequenceDefinitions.map { it.name.substringAfterLast('.').trimIdentifierQuotes() })
        }
        val duplicates = objectNames.groupBy { it.lowercase(Locale.ROOT) }
            .filterValues { it.size > 1 }
            .values
            .flatten()
        require(duplicates.isEmpty()) {
            "PostgreSQL tables, views, and sequences share a namespace; duplicate KeepSchema names: $duplicates"
        }

    }

    private fun validateNames(kind: String, names: List<String>) {
        require(names.all(String::isNotBlank)) { "KeepSchema $kind names must not be blank" }
    }

    private fun sortTablesByReferences(tables: List<Table>): List<Table> {
        val declared = tables.toSet()
        val visited = mutableSetOf<Table>()
        val visiting = mutableSetOf<Table>()
        val result = mutableListOf<Table>()

        fun visit(table: Table) {
            if (table in visited || !visiting.add(table)) return
            table.foreignKeys.map { it.targetTable }.filter { it in declared }.forEach(::visit)
            visiting.remove(table)
            visited += table
            result += table
        }

        tables.forEach(::visit)
        return result
    }

    private fun quoteIdentifier(identifier: String): String =
        "\"${identifier.trimIdentifierQuotes().replace("\"", "\"\"")}\""

    private fun qualifiedName(schema: String, name: String): String =
        "${quoteIdentifier(schema)}.${quoteIdentifier(name.substringAfterLast('.'))}"

    private fun String.trimIdentifierQuotes(): String = removeSurrounding("\"")

    private fun String.withoutTrailingSemicolon(): String = trim().trimEnd(';').trimEnd()

    private fun normalizedStatement(sql: String): String =
        sql.withoutTrailingSemicolon().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun sequenceNameFromCreate(sql: String): String {
        val match = Regex(
            "^CREATE\\s+SEQUENCE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+([^\\s]+)",
            RegexOption.IGNORE_CASE,
        ).find(sql.trim()) ?: error("Not a CREATE SEQUENCE statement: $sql")
        return match.groupValues[1].substringAfterLast('.').trimIdentifierQuotes().lowercase(Locale.ROOT)
    }

    private fun PostgresSequenceDefinition.hasOptions(): Boolean =
        startWith != null || incrementBy != null || minValue != null || maxValue != null ||
            cycle != null || cache != null

    private fun orderedPostgresObjects(objects: List<PostgresSchemaObject>): List<PostgresSchemaObject> =
        objects.sortedBy {
            when (it) {
                is PostgresUniqueConstraintDefinition -> 0
                is PostgresRowTriggerDefinition -> 1
            }
        }

    private data class RenderedTables(
        val sequenceStatements: List<String>,
        val tableStatements: List<String>,
        val afterTableStatements: List<String>,
        val indexStatements: List<String>,
    )
}
