package org.tekfive.keep.migration.dynamic

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
import org.tekfive.keep.schema.PostgresTargetVersion
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
 * Planning reads PostgreSQL metadata and creates short-lived temporary objects to canonicalize
 * types and view queries. Missing extensions and pending column renames are simulated inside a
 * savepoint and rolled back before returning, including on failure. Simulation requires extension
 * installation privileges and, for renames, ALTER privileges and table locks. The returned plan
 * includes extensions and renames followed by the remaining changes; none are committed here.
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

        val extensions = missingExtensionStatements(connection, keepSchema.extensions)
        val renames = resolveColumnRenames(connection, keepSchema.tables)
        val prerequisites = extensions + renames
        return withSimulatedChanges(connection, prerequisites) {
            val remaining = planSchema(connection, keepSchema, inventory, nonDestructive)
            remaining.copy(statements = prerequisites + remaining.statements)
        }
    }

    private fun missingExtensionStatements(connection: Connection, extensions: List<String>): List<CreateExtension> {
        if (extensions.isEmpty()) return emptyList()
        val installed = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT extname FROM pg_extension").use { result ->
                buildSet { while (result.next()) add(result.getString(1)) }
            }
        }
        return extensions.distinct().filterNot(installed::contains).map { CreateExtension(it) }
    }

    private fun planSchema(
        connection: Connection,
        keepSchema: KeepSchema,
        inventory: List<ExistingObject>,
        nonDestructive: Boolean,
    ): PostgresMigrationPlan {
        val candidates = mutableListOf<CandidateStatement>()
        if (!schemaExists(connection, keepSchema.schemaName)) {
            candidates += candidate(CreateSchema(keepSchema.schemaName))
        }

        val existingSequences = inventory.filter { it.kind == ExistingObjectKind.SEQUENCE }
        val existingSequenceNames = existingSequences.mapTo(mutableSetOf()) { it.name }
        keepSchema.declaredSequenceNames
            .filterNot(existingSequenceNames::contains)
            .sorted()
            .forEach { sequenceName ->
                candidates += candidate(CreateSequence(QualifiedName(sequenceName, keepSchema.schemaName)))
            }

        val existingViews = inventory.filter { it.kind.isView }.associateBy { it.name }
        val desiredViewNames = keepSchema.views.mapTo(mutableSetOf()) { it.name }

        val extraMaterializedViews = existingViews.values
            .filter { it.name !in desiredViewNames && it.kind == ExistingObjectKind.MATERIALIZED_VIEW }
            .map { it.name }
        extraMaterializedViews.distinct().sorted().forEach { name ->
            candidates += candidate(DropMaterializedView(QualifiedName(name, keepSchema.schemaName)))
        }

        val extraViews = existingViews.values
            .filter { it.name !in desiredViewNames && it.kind == ExistingObjectKind.VIEW }
            .map { it.name }
        extraViews.distinct().sorted().forEach { name ->
            candidates += candidate(DropView(QualifiedName(name, keepSchema.schemaName)))
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
            ).flatMap(ExposedStatementAdapter::parse)
            val recreatedIndexNames = exposedTableStatements.filterIsInstance<CreateIndex>()
                .map { it.definition.name.name }.toSet()

            postgresColumnTypeStatements(connection, keepSchema.tables, inventory).forEach {
                candidates += candidate(it)
            }
            exposedTableStatements
                // PostgreSQL-native comparison below replaces Exposed's incomplete type detection.
                .filterNot { it is AlterColumnType }
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
        extraOrdinaryTables.distinct().sorted().forEach { name ->
            candidates += candidate(DropTable(QualifiedName(name, keepSchema.schemaName)))
        }

        val extraForeignTables = inventory
            .filter { it.name !in desiredTableNames && it.kind == ExistingObjectKind.FOREIGN_TABLE }
            .map { it.name }
        extraForeignTables.distinct().sorted().forEach { name ->
            candidates += candidate(DropForeignTable(QualifiedName(name, keepSchema.schemaName)))
        }

        val desiredSequenceNames = keepSchema.declaredSequenceNames.toSet()
        val extraSequences = existingSequences
            .filter { !it.ownedByTable && it.name !in desiredSequenceNames }
            .map { it.name }
        extraSequences.distinct().sorted().forEach { name ->
            candidates += candidate(DropSequence(QualifiedName(name, keepSchema.schemaName)))
        }

        if (nonDestructive) {
            // Retained, unmapped columns must allow inserts that only supply declared columns.
            val removedColumns = candidates.map { it.statement }.filterIsInstance<DropColumn>()
            retainedColumnNullabilityStatements(connection, removedColumns).forEach {
                candidates += candidate(it)
            }
        }

        val executable = mutableListOf<PostgresMigrationStatement>()
        val suppressed = mutableListOf<SuppressedPostgresMigrationStatement>()
        candidates.forEach { planned ->
            val statement = planned.statement
            val destructiveChange = planned.forcedDestructiveChange ?: statement.destructiveChange
            if (nonDestructive && destructiveChange != null) {
                suppressed += SuppressedPostgresMigrationStatement(statement, destructiveChange)
            } else {
                executable += statement
            }
        }

        return PostgresMigrationPlan(executable.distinct(), suppressed.distinct())
    }

    private fun retainedColumnNullabilityStatements(
        connection: Connection,
        removedColumns: List<DropColumn>,
    ): List<DropNotNull> = buildList {
        removedColumns.groupBy { it.table }.forEach { (table, columns) ->
            val requiredColumns = connection.prepareStatement(
                """
                SELECT attname FROM pg_attribute
                WHERE attrelid = ?::regclass AND attnum > 0 AND NOT attisdropped AND attnotnull
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, table.toSql())
                statement.executeQuery().use { result ->
                    buildSet { while (result.next()) add(result.getString(1)) }
                }
            }
            columns.filter { it.column in requiredColumns }.forEach {
                add(DropNotNull(table, it.column))
            }
        }
    }

    private fun <T> withSimulatedChanges(
        connection: Connection,
        changes: List<PostgresMigrationStatement>,
        block: () -> T,
    ): T {
        if (changes.isEmpty()) return block()

        val metadata = currentDialectMetadata
        // Keep the caller's earlier writes outside this savepoint. Roll back even after SQL errors,
        // so a caller that catches a planning failure can safely continue its transaction.
        val savepoint = connection.setSavepoint()
        var planningFailure: Throwable? = null
        try {
            connection.createStatement().use { statement -> changes.forEach { statement.execute(it.toSql()) } }
            metadata.resetCaches()
            return block()
        } catch (failure: Throwable) {
            planningFailure = failure
            throw failure
        } finally {
            try {
                try {
                    connection.rollback(savepoint)
                } catch (rollbackFailure: Throwable) {
                    // Never leave a connection usable for committing simulated changes if restoration fails.
                    try {
                        connection.close()
                    } catch (closeFailure: Throwable) {
                        rollbackFailure.addSuppressed(closeFailure)
                    }
                    throw rollbackFailure
                }
                connection.releaseSavepoint(savepoint)
            } catch (cleanupFailure: Throwable) {
                if (planningFailure == null) throw cleanupFailure
                planningFailure.addSuppressed(cleanupFailure)
            } finally {
                metadata.resetCaches()
            }
        }
    }

    private fun postgresColumnTypeStatements(
        connection: Connection,
        tables: List<Table>,
        inventory: List<ExistingObject>,
    ): List<PostgresMigrationStatement> {
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
                            AlterColumnType(SqlCursor(transaction.identity(table)).name(), column.nameUnquoted(),
                                PostgresType(desiredType), SqlExpression(conversion))
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
            CreateMaterializedView(QualifiedName(desired.name, schema), SqlQuery(desired.query.withoutTrailingSemicolon()))
        } else {
            CreateView(QualifiedName(desired.name, schema), SqlQuery(desired.query.withoutTrailingSemicolon()))
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
                        candidate(CreateOrReplaceView(QualifiedName(desired.name, schema), SqlQuery(desired.query.withoutTrailingSemicolon())))
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
            ExistingObjectKind.VIEW -> DropView(QualifiedName(desired.name, schema))
            ExistingObjectKind.MATERIALIZED_VIEW -> DropMaterializedView(QualifiedName(desired.name, schema))
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
        keepSchema.extensions.forEach { validateIdentifier(it, "extension") }
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
        val context = PostgresRenderContext(
            keepSchema.schemaName,
            PostgresTargetVersion(connection.metaData.databaseMajorVersion, connection.metaData.databaseMinorVersion),
        )
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
        statement: PostgresMigrationStatement,
        keepSchema: KeepSchema,
        recreatedIndexNames: Set<String>,
    ): Boolean {
        val droppedIndex = (statement as? DropIndex)?.name?.name
        if (droppedIndex != null) {
            if (recreatedIndexNames.any { it.equals(droppedIndex, ignoreCase = true) }) return false
            val declaredIndices = keepSchema.tables.flatMap { table -> table.indices.map { it.indexName } }
            return declaredIndices.any { it.equals(droppedIndex, ignoreCase = true) }
        }

        val droppedConstraint = (statement as? DropConstraint)?.name
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
        val createStatements = listOf(AddConstraint(
            QualifiedName(definition.table.nameInDatabaseCaseUnquoted(), context.schemaName),
            ConstraintDefinition.Unique(definition.name, definition.columns.map { it.nameUnquoted() }, definition.nullsNotDistinct),
        ))
        val existing = readConstraint(
            connection = connection,
            schemaName = context.schemaName,
            tableName = definition.table.nameInDatabaseCaseUnquoted(),
            constraintName = definition.name,
        ) ?: return createStatements.map(::candidate)

        require(existing.type == "u") {
            "PostgreSQL object ${definition.name} on ${definition.table.tableName} exists as " +
                "constraint type ${existing.type}, not UNIQUE"
        }
        val desiredColumns = definition.columns.map { it.nameUnquoted() }
        if (existing.columns == desiredColumns && existing.nullsNotDistinct == definition.nullsNotDistinct) {
            return emptyList()
        }

        val drop = DropConstraint(QualifiedName(definition.table.nameInDatabaseCaseUnquoted(), context.schemaName), definition.name)
        return listOf(candidate(drop)) + createStatements.map(::candidate)
    }

    private fun planRowTrigger(
        connection: Connection,
        context: PostgresRenderContext,
        definition: PostgresRowTriggerDefinition,
    ): List<CandidateStatement> = buildList {
        val desiredBody = definition.functionBody(context).normalizedFunctionBody()
        val existingFunction = readTriggerFunction(connection, context.schemaName, definition.functionName)
        if (existingFunction == null) {
            add(candidate(CreateOrReplaceFunction(FunctionDefinition(
                name = QualifiedName(definition.functionName, context.schemaName), returns = PostgresType("trigger"), body = SqlBody(desiredBody),
            ))))
        } else {
            require(existingFunction.language == "plpgsql" && existingFunction.returnsTrigger) {
                "Function ${context.qualified(definition.functionName)} exists but is not a PL/pgSQL trigger function"
            }
            if (existingFunction.body.normalizedFunctionBody() != desiredBody) {
                add(candidate(CreateOrReplaceFunction(FunctionDefinition(
                name = QualifiedName(definition.functionName, context.schemaName), returns = PostgresType("trigger"), body = SqlBody(desiredBody),
            ))))
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
                        DropTrigger(QualifiedName(definition.table.nameInDatabaseCaseUnquoted(), context.schemaName), definition.name)
                    )
                )
            }
            add(candidate(CreateTrigger(TriggerDefinition(
                definition.name, QualifiedName(definition.table.nameInDatabaseCaseUnquoted(), context.schemaName),
                QualifiedName(definition.functionName, context.schemaName), TriggerTiming.valueOf(definition.timing.name),
                definition.events.map { TriggerEvent.valueOf(it.name) }.toSet(),
            ))))
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
               array_agg(a.attname ORDER BY key_columns.ordinality),
               ${if (connection.metaData.databaseMajorVersion >= 15) "bool_or(i.indnullsnotdistinct)" else "FALSE"}
        FROM pg_constraint c
        LEFT JOIN pg_index i ON i.indexrelid = c.conindid
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
            ExistingConstraint(result.getString(1), columns, result.getBoolean(3))
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
              AND NOT EXISTS (
                  SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_class'::regclass
                    AND d.objid = c.oid
                    AND d.refclassid = 'pg_extension'::regclass
                    AND d.deptype = 'e'
              )
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

    private fun validateIdentifier(identifier: String, objectType: String) {
        require(identifier.isNotBlank()) { "PostgreSQL $objectType name must not be blank" }
        require('\u0000' !in identifier) { "PostgreSQL $objectType name must not contain a NUL character" }
        require(identifier.toByteArray(StandardCharsets.UTF_8).size <= POSTGRES_IDENTIFIER_BYTES) {
            "PostgreSQL $objectType name exceeds $POSTGRES_IDENTIFIER_BYTES UTF-8 bytes: $identifier"
        }
    }

    private fun candidate(statement: PostgresMigrationStatement): CandidateStatement = CandidateStatement(statement)

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
        val statement: PostgresMigrationStatement,
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
        val nullsNotDistinct: Boolean,
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
