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
import org.tekfive.keep.schema.PostgresRenderContext
import org.tekfive.keep.schema.PostgresRowTriggerDefinition
import org.tekfive.keep.schema.PostgresSchemaObject
import org.tekfive.keep.schema.PostgresTriggerEvent
import org.tekfive.keep.schema.PostgresTriggerTiming
import org.tekfive.keep.schema.PostgresUniqueConstraintDefinition
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
            val recreatedIndexNames = exposedTableStatements.mapNotNull { sql ->
                CREATE_INDEX_NAME.find(sql)?.groupValues?.get(1)?.unqualifiedIdentifier()
            }.toSet()

            postgresColumnTypeStatements(connection, keepSchema.tables, inventory).forEach {
                candidates += candidate(it)
            }
            exposedTableStatements
                // PostgreSQL-native comparison below replaces Exposed's incomplete type detection.
                .filterNot { destructiveChangeFor(it) == DestructivePostgresMigrationChange.ALTER_COLUMN_TYPE }
                // Exposed can misclassify multiple partial indexes on the same columns, and it
                // does not know about KEEP's first-class PostgreSQL constraints.
                .filterNot { dropsDeclaredPostgresObject(it, keepSchema, recreatedIndexNames) }
                .forEach { candidates += candidate(it) }
        }

        candidates += planPostgresObjects(connection, keepSchema)

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
                        val conversion = postgresTypeConversion(existingType, desiredType, columnName)
                        add(
                            "ALTER TABLE ${transaction.identity(table)} " +
                                "ALTER COLUMN $columnName TYPE $desiredType USING ($conversion)"
                        )
                    }
                }
            }
        }
    }

    private fun postgresTypeConversion(existingType: String, desiredType: String, columnName: String): String {
        val existing = existingType.lowercase(Locale.ROOT)
        val desired = desiredType.lowercase(Locale.ROOT)
        val existingIsTimestampWithTimeZone = existing.startsWith("timestamp") && existing.contains("with time zone")
        val desiredIsTimestampWithTimeZone = desired.startsWith("timestamp") && desired.contains("with time zone")

        return when {
            existing == "bigint" && desiredIsTimestampWithTimeZone ->
                "to_timestamp($columnName / 1000.0)"

            existingIsTimestampWithTimeZone && desired == "bigint" ->
                "floor(extract(epoch from $columnName) * 1000)::bigint"

            else -> "$columnName::$desiredType"
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
    }

    private fun planPostgresObjects(
        connection: Connection,
        keepSchema: KeepSchema,
    ): List<CandidateStatement> {
        val context = PostgresRenderContext(keepSchema.schemaName)
        return buildList {
            keepSchema.declaredPostgresObjects
                .sortedBy { if (it is PostgresUniqueConstraintDefinition) 0 else 1 }
                .forEach { definition ->
                    when (definition) {
                        is PostgresUniqueConstraintDefinition ->
                            addAll(planUniqueConstraint(connection, context, definition))
                        is PostgresRowTriggerDefinition ->
                            addAll(planRowTrigger(connection, context, definition))
                    }
                }
        }
    }

    private fun dropsDeclaredPostgresObject(
        sql: String,
        keepSchema: KeepSchema,
        recreatedIndexNames: Set<String>,
    ): Boolean {
        val droppedIndex = DROP_INDEX_NAME.find(sql)?.groupValues?.get(1)?.unqualifiedIdentifier()
        if (droppedIndex != null) {
            if (recreatedIndexNames.any { it.equals(droppedIndex, ignoreCase = true) }) return false
            val declaredIndices = keepSchema.tables.flatMap { table -> table.indices.map { it.indexName } }
            return declaredIndices.any { it.equals(droppedIndex, ignoreCase = true) }
        }

        val droppedConstraint = DROP_CONSTRAINT_NAME.find(sql)?.groupValues?.get(1)?.unqualifiedIdentifier()
            ?: return false
        return keepSchema.declaredPostgresObjects
            .filterIsInstance<PostgresUniqueConstraintDefinition>()
            .any { it.name.equals(droppedConstraint, ignoreCase = true) }
    }

    private fun planUniqueConstraint(
        connection: Connection,
        context: PostgresRenderContext,
        definition: PostgresUniqueConstraintDefinition,
    ): List<CandidateStatement> {
        val existing = readConstraint(
            connection = connection,
            schemaName = context.schemaName,
            tableName = definition.table.nameInDatabaseCaseUnquoted(),
            constraintName = definition.name,
        ) ?: return definition.createStatements(context).map(::candidate)

        require(existing.type == "u") {
            "PostgreSQL object ${definition.name} on ${definition.table.tableName} exists as " +
                "constraint type ${existing.type}, not UNIQUE"
        }
        val desiredColumns = definition.columns.map { it.nameUnquoted() }
        if (existing.columns == desiredColumns) return emptyList()

        val drop = "ALTER TABLE ${context.tableName(definition.table)} " +
            "DROP CONSTRAINT ${context.identifier(definition.name)}"
        return listOf(candidate(drop)) + definition.createStatements(context).map(::candidate)
    }

    private fun planRowTrigger(
        connection: Connection,
        context: PostgresRenderContext,
        definition: PostgresRowTriggerDefinition,
    ): List<CandidateStatement> = buildList {
        val desiredBody = definition.functionBody(context).normalizedFunctionBody()
        val existingFunction = readTriggerFunction(connection, context.schemaName, definition.functionName)
        if (existingFunction == null) {
            add(candidate(definition.createFunctionStatement(context)))
        } else {
            require(existingFunction.language == "plpgsql" && existingFunction.returnsTrigger) {
                "Function ${context.qualified(definition.functionName)} exists but is not a PL/pgSQL trigger function"
            }
            if (existingFunction.body.normalizedFunctionBody() != desiredBody) {
                add(candidate(definition.createFunctionStatement(context)))
            }
        }

        val existingTrigger = readRowTrigger(
            connection = connection,
            schemaName = context.schemaName,
            tableName = definition.table.nameInDatabaseCaseUnquoted(),
            triggerName = definition.name,
        )
        val desiredType = triggerType(definition.timing, definition.events)
        val triggerMatches = existingTrigger != null &&
            existingTrigger.type == desiredType &&
            existingTrigger.functionSchema == context.schemaName &&
            existingTrigger.functionName == definition.functionName

        if (!triggerMatches) {
            if (existingTrigger != null) {
                add(
                    candidate(
                        "DROP TRIGGER ${context.identifier(definition.name)} " +
                            "ON ${context.tableName(definition.table)}"
                    )
                )
            }
            add(candidate(definition.createTriggerStatement(context)))
        }
    }

    private fun readConstraint(
        connection: Connection,
        schemaName: String,
        tableName: String,
        constraintName: String,
    ): ExistingConstraint? = connection.prepareStatement(
        """
        SELECT c.contype::text,
               array_agg(a.attname ORDER BY key_columns.ordinality)
        FROM pg_constraint c
        JOIN pg_class relation ON relation.oid = c.conrelid
        JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
        LEFT JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS key_columns(attnum, ordinality) ON TRUE
        LEFT JOIN pg_attribute a ON a.attrelid = relation.oid AND a.attnum = key_columns.attnum
        WHERE namespace.nspname = ? AND relation.relname = ? AND c.conname = ?
        GROUP BY c.oid, c.contype
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, schemaName)
        statement.setString(2, tableName)
        statement.setString(3, constraintName)
        statement.executeQuery().use { result ->
            if (!result.next()) return@use null
            val columns = (result.getArray(2)?.array as? Array<*>)
                .orEmpty()
                .map { it.toString() }
            ExistingConstraint(result.getString(1), columns)
        }
    }

    private fun readTriggerFunction(
        connection: Connection,
        schemaName: String,
        functionName: String,
    ): ExistingTriggerFunction? = connection.prepareStatement(
        """
        SELECT procedure.prosrc,
               language.lanname,
               procedure.prorettype = 'pg_catalog.trigger'::regtype
        FROM pg_proc procedure
        JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
        JOIN pg_language language ON language.oid = procedure.prolang
        WHERE namespace.nspname = ? AND procedure.proname = ? AND procedure.pronargs = 0
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, schemaName)
        statement.setString(2, functionName)
        statement.executeQuery().use { result ->
            if (!result.next()) return@use null
            ExistingTriggerFunction(
                body = result.getString(1),
                language = result.getString(2),
                returnsTrigger = result.getBoolean(3),
            )
        }
    }

    private fun readRowTrigger(
        connection: Connection,
        schemaName: String,
        tableName: String,
        triggerName: String,
    ): ExistingRowTrigger? = connection.prepareStatement(
        """
        SELECT trigger.tgtype,
               function_namespace.nspname,
               function.proname
        FROM pg_trigger trigger
        JOIN pg_class relation ON relation.oid = trigger.tgrelid
        JOIN pg_namespace relation_namespace ON relation_namespace.oid = relation.relnamespace
        JOIN pg_proc function ON function.oid = trigger.tgfoid
        JOIN pg_namespace function_namespace ON function_namespace.oid = function.pronamespace
        WHERE relation_namespace.nspname = ?
          AND relation.relname = ?
          AND trigger.tgname = ?
          AND NOT trigger.tgisinternal
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, schemaName)
        statement.setString(2, tableName)
        statement.setString(3, triggerName)
        statement.executeQuery().use { result ->
            if (!result.next()) return@use null
            ExistingRowTrigger(
                type = result.getInt(1),
                functionSchema = result.getString(2),
                functionName = result.getString(3),
            )
        }
    }

    private fun triggerType(
        timing: PostgresTriggerTiming,
        events: Set<PostgresTriggerEvent>,
    ): Int = TRIGGER_TYPE_ROW or
        (if (timing == PostgresTriggerTiming.BEFORE) TRIGGER_TYPE_BEFORE else 0) or
        events.fold(0) { result, event ->
            result or when (event) {
                PostgresTriggerEvent.INSERT -> TRIGGER_TYPE_INSERT
                PostgresTriggerEvent.UPDATE -> TRIGGER_TYPE_UPDATE
                PostgresTriggerEvent.DELETE -> TRIGGER_TYPE_DELETE
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

    private data class ExistingConstraint(
        val type: String,
        val columns: List<String>,
    )

    private data class ExistingTriggerFunction(
        val body: String,
        val language: String,
        val returnsTrigger: Boolean,
    )

    private data class ExistingRowTrigger(
        val type: Int,
        val functionSchema: String,
        val functionName: String,
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
    private const val TRIGGER_TYPE_ROW = 1
    private const val TRIGGER_TYPE_BEFORE = 2
    private const val TRIGGER_TYPE_INSERT = 4
    private const val TRIGGER_TYPE_DELETE = 8
    private const val TRIGGER_TYPE_UPDATE = 16
    private val WHITESPACE = Regex("\\s+")
}

private fun String.normalizedFunctionBody(): String =
    replace("\r\n", "\n").trim()

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
private val DROP_INDEX_NAME = Regex("(?i)\\bDROP\\s+INDEX(?:\\s+IF\\s+EXISTS)?\\s+([^\\s,;]+)")
private val CREATE_INDEX_NAME =
    Regex("(?i)\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+([^\\s,;]+)")
private val DROP_CONSTRAINT_NAME = Regex("(?i)\\bDROP\\s+CONSTRAINT(?:\\s+IF\\s+EXISTS)?\\s+([^\\s,;]+)")

private fun String.unqualifiedIdentifier(): String =
    substringAfterLast('.').trim().removeSurrounding("\"")
