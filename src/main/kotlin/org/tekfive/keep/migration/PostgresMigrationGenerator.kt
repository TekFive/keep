package org.tekfive.keep.migration

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.tekfive.keep.db.dbConnection
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresViewDefinition
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.sql.Connection
import java.util.Locale
import java.util.UUID

/**
 * Produces PostgreSQL SQL by comparing a [KeepSchema] with the current database schema.
 *
 * Planning reads PostgreSQL metadata and creates short-lived temporary views to let PostgreSQL
 * canonicalize desired view queries. It never applies any statement in the returned plan.
 */
object PostgresMigrationGenerator {

    fun generate(
        database: Database,
        keepSchema: KeepSchema,
        output: Path,
        nonDestructive: Boolean,
        overwrite: Boolean = false,
    ): PostgresMigrationPlan = plan(database, keepSchema, nonDestructive).also {
        it.writeTo(output, overwrite)
    }

    fun plan(
        database: Database,
        keepSchema: KeepSchema,
        nonDestructive: Boolean,
    ): PostgresMigrationPlan = transaction(database) {
        plan(keepSchema, nonDestructive)
    }

    /** Must be called inside an Exposed JDBC transaction. */
    fun plan(
        keepSchema: KeepSchema,
        nonDestructive: Boolean,
    ): PostgresMigrationPlan {
        require(currentDialect is PostgreSQLDialect) {
            "PostgresMigrationGenerator only supports PostgreSQL; current dialect is ${currentDialect.name}"
        }

        validateKeepSchema(keepSchema)
        currentDialectMetadata.resetCaches()

        val connection = dbConnection()
        validateTableSchemas(connection, keepSchema)

        val inventory = readInventory(connection, keepSchema.schemaName)
        validateExistingObjectKinds(keepSchema, inventory)

        val candidates = mutableListOf<CandidateStatement>()
        if (!schemaExists(connection, keepSchema.schemaName)) {
            candidates += candidate("CREATE SCHEMA ${quoteIdentifier(keepSchema.schemaName)}")
        }

        val existingSequences = inventory.filter { it.kind == ExistingObjectKind.SEQUENCE }
        val existingSequenceNames = existingSequences.mapTo(mutableSetOf()) { it.name }
        keepSchema.declaredSequenceNames
            .filterNot(existingSequenceNames::contains)
            .sorted()
            .forEach { sequenceName ->
                candidates += candidate("CREATE SEQUENCE ${qualifiedName(keepSchema.schemaName, sequenceName)}")
            }

        val existingViews = inventory.filter { it.kind.isView }.associateBy { it.name }
        val desiredViewNames = keepSchema.views.mapTo(mutableSetOf()) { it.name }

        val extraMaterializedViews = existingViews.values
            .filter { it.name !in desiredViewNames && it.kind == ExistingObjectKind.MATERIALIZED_VIEW }
            .map { it.name }
        combinedDrop("DROP MATERIALIZED VIEW", keepSchema.schemaName, extraMaterializedViews)?.let {
            candidates += candidate(it)
        }

        val extraViews = existingViews.values
            .filter { it.name !in desiredViewNames && it.kind == ExistingObjectKind.VIEW }
            .map { it.name }
        combinedDrop("DROP VIEW", keepSchema.schemaName, extraViews)?.let {
            candidates += candidate(it)
        }

        val viewPlans = keepSchema.views.map { desired ->
            planView(connection, keepSchema.schemaName, desired, existingViews[desired.name])
        }
        // The caller supplies views in dependency order: drop in reverse, recreate in forward order.
        viewPlans.asReversed().forEach { candidates += it.beforeTables }
        val postTableViewStatements = viewPlans.flatMap { it.afterTables }

        if (keepSchema.tables.isNotEmpty()) {
            val exposedTableStatements = MigrationUtils.statementsRequiredForDatabaseMigration(
                *keepSchema.tables.toTypedArray(),
                withLogs = false,
            ).flatMap(::expandPostgresAlterTableStatement)

            postgresColumnTypeStatements(connection, keepSchema.tables, inventory).forEach {
                candidates += candidate(it)
            }
            exposedTableStatements
                // PostgreSQL-native comparison below replaces Exposed's incomplete type detection.
                .filterNot { destructiveChangeFor(it) == DestructivePostgresMigrationChange.ALTER_COLUMN_TYPE }
                .forEach { candidates += candidate(it) }
        }

        candidates += postTableViewStatements

        val desiredTableNames = keepSchema.tables.mapTo(mutableSetOf()) { it.nameInDatabaseCaseUnquoted() }
        val extraOrdinaryTables = inventory
            .filter {
                !it.isPartition &&
                    it.name !in desiredTableNames &&
                    it.kind in setOf(ExistingObjectKind.TABLE, ExistingObjectKind.PARTITIONED_TABLE)
            }
            .map { it.name }
        combinedDrop("DROP TABLE", keepSchema.schemaName, extraOrdinaryTables)?.let {
            candidates += candidate(it)
        }

        val extraForeignTables = inventory
            .filter { it.name !in desiredTableNames && it.kind == ExistingObjectKind.FOREIGN_TABLE }
            .map { it.name }
        combinedDrop("DROP FOREIGN TABLE", keepSchema.schemaName, extraForeignTables)?.let {
            candidates += candidate(it)
        }

        val desiredSequenceNames = keepSchema.declaredSequenceNames.toSet()
        val extraSequences = existingSequences
            .filter { !it.ownedByTable && it.name !in desiredSequenceNames }
            .map { it.name }
        combinedDrop("DROP SEQUENCE", keepSchema.schemaName, extraSequences)?.let {
            candidates += candidate(it)
        }

        val executable = mutableListOf<String>()
        val suppressed = mutableListOf<SuppressedPostgresMigrationStatement>()
        candidates.forEach { planned ->
            val sql = planned.sql.withoutTrailingSemicolon()
            val destructiveChange = planned.forcedDestructiveChange ?: destructiveChangeFor(sql)
            if (nonDestructive && destructiveChange != null) {
                suppressed += SuppressedPostgresMigrationStatement(sql, destructiveChange)
            } else {
                executable += sql
            }
        }

        return PostgresMigrationPlan(executable.distinct(), suppressed.distinct())
    }

    private fun postgresColumnTypeStatements(
        connection: Connection,
        tables: List<Table>,
        inventory: List<ExistingObject>,
    ): List<String> {
        val existingTables = inventory
            .filter { it.kind.isTable }
            .mapTo(mutableSetOf()) { it.name }
        val transaction = TransactionManager.current()

        return buildList {
            tables.filter { it.nameInDatabaseCaseUnquoted() in existingTables }.forEach { table ->
                val existingTypes = readRelationColumns(connection, transaction.identity(table))
                    .associate { it.name.lowercase(Locale.ROOT) to it.sqlType }
                val desiredTypes = canonicalDesiredColumnTypes(connection, table)

                table.columns.forEach { column ->
                    val existingType = existingTypes[column.nameUnquoted().lowercase(Locale.ROOT)] ?: return@forEach
                    val desiredType = desiredTypes.getValue(column)
                    if (!existingType.equals(desiredType, ignoreCase = true)) {
                        val columnName = transaction.identity(column)
                        requireAutomaticTypeMigration(existingType, desiredType, transaction.identity(table), columnName)
                        add(
                            "ALTER TABLE ${transaction.identity(table)} " +
                                "ALTER COLUMN $columnName TYPE $desiredType USING $columnName::$desiredType"
                        )
                    }
                }
            }
        }
    }

    private fun requireAutomaticTypeMigration(
        existingType: String,
        desiredType: String,
        tableName: String,
        columnName: String,
    ) {
        val existing = existingType.lowercase(Locale.ROOT)
        val desired = desiredType.lowercase(Locale.ROOT)
        val integerTypes = setOf("smallint", "integer", "bigint")
        require(!((existing in integerTypes && desired == "uuid") || (existing == "uuid" && desired in integerTypes))) {
            "Cannot automatically migrate $tableName.$columnName from $existingType to $desiredType. " +
                "Changing between sequence-backed integer and UUID identities requires a manual additive migration " +
                "that creates and backfills new primary-key and foreign-key columns before swapping them."
        }
    }

    private fun canonicalDesiredColumnTypes(connection: Connection, table: Table): Map<Column<*>, String> {
        if (table.columns.isEmpty()) return emptyMap()

        val temporaryName = "keep_types_${UUID.randomUUID().toString().replace("-", "")}"
        val definitions = table.columns.mapIndexed { index, column ->
            "${quoteIdentifier("column_$index")} ${column.columnType.sqlType()}"
        }
        var created = false
        try {
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TEMPORARY TABLE ${quoteIdentifier(temporaryName)} (${definitions.joinToString()})"
                )
                created = true
            }
            val canonicalTypes = readRelationColumns(
                connection,
                "pg_temp.${quoteIdentifier(temporaryName)}",
            ).map { it.sqlType }
            check(canonicalTypes.size == table.columns.size) {
                "PostgreSQL returned ${canonicalTypes.size} canonical column types for " +
                    "${table.columns.size} columns in ${table.tableName}"
            }
            return table.columns.zip(canonicalTypes).toMap()
        } finally {
            if (created) {
                connection.createStatement().use { statement ->
                    statement.execute("DROP TABLE pg_temp.${quoteIdentifier(temporaryName)}")
                }
            }
        }
    }

    /** Must be called inside an Exposed JDBC transaction. */
    fun generate(
        keepSchema: KeepSchema,
        output: Path,
        nonDestructive: Boolean,
        overwrite: Boolean = false,
    ): PostgresMigrationPlan = plan(keepSchema, nonDestructive).also {
        it.writeTo(output, overwrite)
    }

    private fun planView(
        connection: Connection,
        schema: String,
        desired: PostgresViewDefinition,
        existing: ExistingObject?,
    ): ViewPlan {
        val qualifiedName = qualifiedName(schema, desired.name)
        val create = if (desired.materialized) {
            "CREATE MATERIALIZED VIEW $qualifiedName AS ${desired.query.withoutTrailingSemicolon()}"
        } else {
            "CREATE VIEW $qualifiedName AS ${desired.query.withoutTrailingSemicolon()}"
        }
        if (existing == null) return ViewPlan(afterTables = listOf(candidate(create)))

        val desiredKind = if (desired.materialized) {
            ExistingObjectKind.MATERIALIZED_VIEW
        } else {
            ExistingObjectKind.VIEW
        }
        val desiredDefinition = canonicalizeViewQuery(connection, desired.query)
        val existingDefinition = readCanonicalView(connection, qualifiedName)
        if (existing.kind == desiredKind && existingDefinition == desiredDefinition) return ViewPlan()

        if (existing.kind == ExistingObjectKind.VIEW && desiredKind == ExistingObjectKind.VIEW) {
            if (existingDefinition.columns == desiredDefinition.columns) {
                return ViewPlan(
                    afterTables = listOf(
                        candidate("CREATE OR REPLACE VIEW $qualifiedName AS ${desired.query.withoutTrailingSemicolon()}")
                    )
                )
            }
        }

        val dropChange = when (existing.kind) {
            ExistingObjectKind.VIEW -> DestructivePostgresMigrationChange.DROP_VIEW
            ExistingObjectKind.MATERIALIZED_VIEW -> DestructivePostgresMigrationChange.DROP_MATERIALIZED_VIEW
            else -> error("Expected a view but found ${existing.kind}")
        }
        val drop = when (existing.kind) {
            ExistingObjectKind.VIEW -> "DROP VIEW $qualifiedName"
            ExistingObjectKind.MATERIALIZED_VIEW -> "DROP MATERIALIZED VIEW $qualifiedName"
        }
        return ViewPlan(
            beforeTables = listOf(candidate(drop)),
            // Re-creation cannot run unless the destructive replacement step is also allowed.
            afterTables = listOf(CandidateStatement(create, dropChange)),
        )
    }

    private fun canonicalizeViewQuery(connection: Connection, query: String): CanonicalView {
        val cleanQuery = query.withoutTrailingSemicolon()
        require(cleanQuery.isNotBlank()) { "View query must not be blank" }

        val temporaryName = "keep_view_${UUID.randomUUID().toString().replace("-", "")}"
        var created = false
        try {
            connection.createStatement().use { statement ->
                statement.execute("CREATE TEMPORARY VIEW ${quoteIdentifier(temporaryName)} AS $cleanQuery")
                created = true
            }
            return readCanonicalView(connection, "pg_temp.${quoteIdentifier(temporaryName)}")
        } finally {
            if (created) {
                connection.createStatement().use { statement ->
                    statement.execute("DROP VIEW pg_temp.${quoteIdentifier(temporaryName)}")
                }
            }
        }
    }

    private fun readCanonicalView(connection: Connection, regclassName: String): CanonicalView {
        val definition = connection.prepareStatement("SELECT pg_get_viewdef(?::regclass, true)").use { statement ->
            statement.setString(1, regclassName)
            statement.executeQuery().use { result ->
                check(result.next()) { "PostgreSQL did not return a view definition for $regclassName" }
                normalizeViewQuery(result.getString(1))
            }
        }
        val columns = readRelationColumns(connection, regclassName)
        return CanonicalView(definition, columns)
    }

    private fun readRelationColumns(connection: Connection, regclassName: String): List<RelationColumn> =
        connection.prepareStatement(
            """
            SELECT a.attname, format_type(a.atttypid, a.atttypmod)
            FROM pg_attribute a
            WHERE a.attrelid = ?::regclass
              AND a.attnum > 0
              AND NOT a.attisdropped
            ORDER BY a.attnum
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, regclassName)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(RelationColumn(result.getString(1), result.getString(2)))
                    }
                }
            }
        }

    private fun validateKeepSchema(keepSchema: KeepSchema) {
        validateIdentifier(keepSchema.schemaName, "schema")
        keepSchema.views.forEach {
            validateIdentifier(it.name, "view")
            require(it.query.withoutTrailingSemicolon().isNotBlank()) { "View ${it.name} has a blank query" }
        }
        keepSchema.declaredSequenceNames.forEach { validateIdentifier(it, "sequence") }

        val tableNames = keepSchema.tables.map { it.tableName.substringAfterLast('.').trim('"') }
        val allNames = tableNames + keepSchema.views.map { it.name } + keepSchema.declaredSequenceNames
        val duplicates = allNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) {
            "PostgreSQL tables, views, and sequences share a namespace; duplicate KeepSchema names: $duplicates"
        }
    }

    private fun validateTableSchemas(connection: Connection, keepSchema: KeepSchema) {
        val currentSchema = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT current_schema()").use { result ->
                check(result.next()) { "PostgreSQL did not return current_schema()" }
                result.getString(1)
            }
        }
        keepSchema.tables.forEach { table ->
            val declaredSchema = table.schemaName
            if (declaredSchema == null) {
                require(currentSchema == keepSchema.schemaName) {
                    "Unqualified table ${table.tableName} resolves in schema $currentSchema, " +
                        "not KeepSchema ${keepSchema.schemaName}"
                }
            } else {
                require(declaredSchema.trim('"') == keepSchema.schemaName) {
                    "Table ${table.tableName} is in schema $declaredSchema, " +
                        "not KeepSchema ${keepSchema.schemaName}"
                }
            }
        }
    }

    private fun validateExistingObjectKinds(
        keepSchema: KeepSchema,
        inventory: List<ExistingObject>,
    ) {
        val existingByName = inventory.associateBy { it.name }
        keepSchema.tables.forEach { table ->
            val name = table.nameInDatabaseCaseUnquoted()
            val existing = existingByName[name] ?: return@forEach
            require(existing.kind.isTable) {
                "KeepSchema table $name conflicts with existing PostgreSQL ${existing.kind.description}"
            }
        }
        keepSchema.views.forEach { view ->
            val existing = existingByName[view.name] ?: return@forEach
            require(existing.kind.isView) {
                "KeepSchema view ${view.name} conflicts with existing PostgreSQL ${existing.kind.description}"
            }
        }
        keepSchema.declaredSequenceNames.forEach { sequence ->
            val existing = existingByName[sequence] ?: return@forEach
            require(existing.kind == ExistingObjectKind.SEQUENCE) {
                "KeepSchema sequence $sequence conflicts with existing PostgreSQL ${existing.kind.description}"
            }
        }
    }

    private fun schemaExists(connection: Connection, schema: String): Boolean =
        connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = ?)").use { statement ->
            statement.setString(1, schema)
            statement.executeQuery().use { result ->
                result.next()
                result.getBoolean(1)
            }
        }

    private fun readInventory(connection: Connection, schema: String): List<ExistingObject> {
        val sql = """
            SELECT c.relname,
                   c.relkind,
                   c.relispartition,
                   CASE WHEN c.relkind = 'S' THEN EXISTS (
                       SELECT 1
                       FROM pg_depend d
                       WHERE d.classid = 'pg_class'::regclass
                         AND d.objid = c.oid
                         AND d.refclassid = 'pg_class'::regclass
                         AND d.deptype IN ('a', 'i')
                   ) ELSE FALSE END
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND c.relkind IN ('r', 'p', 'f', 'v', 'm', 'S')
        """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, schema)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            ExistingObject(
                                name = result.getString(1),
                                kind = ExistingObjectKind.fromPostgresCode(result.getString(2)),
                                isPartition = result.getBoolean(3),
                                ownedByTable = result.getBoolean(4),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun combinedDrop(keyword: String, schema: String, names: List<String>): String? = names
        .distinct()
        .sorted()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ", prefix = "$keyword ") { qualifiedName(schema, it) }

    private fun validateIdentifier(identifier: String, objectType: String) {
        require(identifier.isNotBlank()) { "PostgreSQL $objectType name must not be blank" }
        require('\u0000' !in identifier) { "PostgreSQL $objectType name must not contain a NUL character" }
        require(identifier.toByteArray(StandardCharsets.UTF_8).size <= POSTGRES_IDENTIFIER_BYTES) {
            "PostgreSQL $objectType name exceeds $POSTGRES_IDENTIFIER_BYTES UTF-8 bytes: $identifier"
        }
    }

    private fun candidate(sql: String): CandidateStatement = CandidateStatement(sql.withoutTrailingSemicolon())

    private fun qualifiedName(schema: String, name: String): String =
        "${quoteIdentifier(schema)}.${quoteIdentifier(name)}"

    private fun quoteIdentifier(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    private fun normalizeViewQuery(query: String?): String = query
        .orEmpty()
        .withoutTrailingSemicolon()
        .replace(WHITESPACE, " ")
        .trim()

    private fun String.withoutTrailingSemicolon(): String = trim().trimEnd(';').trimEnd()

    private data class CandidateStatement(
        val sql: String,
        val forcedDestructiveChange: DestructivePostgresMigrationChange? = null,
    )

    private data class ViewPlan(
        val beforeTables: List<CandidateStatement> = emptyList(),
        val afterTables: List<CandidateStatement> = emptyList(),
    )

    private data class ExistingObject(
        val name: String,
        val kind: ExistingObjectKind,
        val isPartition: Boolean,
        val ownedByTable: Boolean,
    )

    private data class CanonicalView(
        val query: String,
        val columns: List<RelationColumn>,
    )

    private data class RelationColumn(
        val name: String,
        val sqlType: String,
    )

    private enum class ExistingObjectKind(
        val postgresCode: String,
        val description: String,
        val isTable: Boolean = false,
        val isView: Boolean = false,
    ) {
        TABLE("r", "table", isTable = true),
        PARTITIONED_TABLE("p", "partitioned table", isTable = true),
        FOREIGN_TABLE("f", "foreign table", isTable = true),
        VIEW("v", "view", isView = true),
        MATERIALIZED_VIEW("m", "materialized view", isView = true),
        SEQUENCE("S", "sequence"),
        ;

        companion object {
            fun fromPostgresCode(code: String): ExistingObjectKind = entries.firstOrNull {
                it.postgresCode == code
            } ?: error("Unsupported PostgreSQL pg_class.relkind: $code")
        }
    }

    private const val POSTGRES_IDENTIFIER_BYTES = 63
    private val WHITESPACE = Regex("\\s+")
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
