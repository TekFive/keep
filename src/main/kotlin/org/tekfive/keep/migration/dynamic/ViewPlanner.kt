package org.tekfive.keep.migration.dynamic

import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.orderedViews
import java.sql.Connection
import java.util.UUID

internal data class ViewMigrationPlan(
    val drops: List<PostgresMigrationStatement>,
    val creates: List<PostgresMigrationStatement>,
    val suppressed: List<SuppressedPostgresMigrationStatement>,
)

/** Compare server-normalized queries and ordered output names/types/collations in a rollback-only savepoint. */
internal fun planViews(
    connection: Connection,
    schema: KeepSchema,
    tableChanges: List<PostgresMigrationStatement>,
    nonDestructive: Boolean,
): ViewMigrationPlan {
    val existing = readViews(connection, schema.schemaName)
    if (existing.isEmpty() && schema.views.isEmpty()) return ViewMigrationPlan(emptyList(), emptyList(), emptyList())
    val definitions = schema.orderedViews().associateBy { it.name }
    val dependencies = readViewDependencies(connection, schema.schemaName)
    val existingOrder = orderViews(existing.keys, dependencies)
    val removed = existing.keys - definitions.keys
    val drops = linkedSetOf<String>()
    val creates = linkedMapOf<String, PostgresMigrationStatement>()
    val desiredDependencies = mutableMapOf<String, Set<String>>()
    val savepoint = connection.setSavepoint()
    var failure: Throwable? = null
    try {
        fun dropWithDependents(names: Set<String>) {
            val affected = names.toMutableSet()
            do {
                val added = dependencies.filter { (_, sources) -> sources.any { it in affected } }.keys - affected
                affected += added
            } while (added.isNotEmpty())
            existingOrder.asReversed().filter { it in affected && it !in drops }.forEach { name ->
                // RESTRICT deliberately rejects dependencies outside the managed schema. Never use CASCADE.
                connection.executeViewSql(existing.getValue(name).drop(schema.schemaName).toSql())
                drops += name
            }
        }

        if (!nonDestructive) dropWithDependents(removed)
        val structuralChanges = tableChanges.filter {
            it is CreateSchema || it is CreateTable || it is AddColumn || it is DropColumn || it is AlterColumnType
        }
        val changingTables = structuralChanges.mapNotNull {
            when (it) {
                is AlterColumnType -> it.table.name
                is DropColumn -> it.table.name
                else -> null
            }
        }.toSet()
        dropWithDependents(dependencies.filterValues { sources -> sources.any { it in changingTables } }.keys)
        structuralChanges.forEach { change ->
            // Defaults and identity values are unnecessary for query analysis and may consume sequences.
            val shape = when (change) {
                is CreateTable -> change.copy(columns = change.columns.map { it.viewShape() },
                    constraints = change.constraints.filter { it is ConstraintDefinition.PrimaryKey || it is ConstraintDefinition.Unique })
                is AddColumn -> change.copy(column = change.column.viewShape())
                else -> change
            }
            connection.executeViewSql(shape.toSql())
        }

        // Declared references are authoritative; known dependencies order legacy raw SQL definitions.
        val ordering = dependencies + definitions.mapValues { (_, view) ->
            if (view.references.isEmpty()) dependencies[view.name].orEmpty()
            else view.references.map { it.tableName.substringAfterLast('.').removeSurrounding("\"") }.toSet()
        }
        val desiredNames = definitions.keys + if (nonDestructive) removed else emptySet()
        orderViews(desiredNames, ordering).forEach { name ->
            val view = definitions[name]
            val old = existing[name]
            val query = view?.renderQuery() ?: old!!.definition.query
            val materialized = view?.materialized ?: old!!.materialized
            val canonical = canonicalizeQuery(connection, query)
            val changed = old == null || old.materialized != materialized || old.definition != canonical
            if (changed || name in drops) {
                if (old != null && !old.materialized && !materialized && name !in drops &&
                    old.definition.columns == canonical.columns) {
                    val replace = CreateOrReplaceView(QualifiedName(name, schema.schemaName), SqlQuery(query))
                    creates[name] = replace
                    connection.executeViewSql(replace.toSql())
                    return@forEach
                }
                if (old != null && name !in drops) dropWithDependents(setOf(name))
                val create = if (materialized) CreateMaterializedView(QualifiedName(name, schema.schemaName), SqlQuery(query))
                else CreateView(QualifiedName(name, schema.schemaName), SqlQuery(query))
                creates[name] = create
                // Materialized views are parsed without executing their defining SELECT during planning.
                connection.executeViewSql(if (create is CreateMaterializedView) create.copy(withData = false).toSql() else create.toSql())
            }
        }
        desiredDependencies += readViewDependencies(connection, schema.schemaName)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            try {
                connection.rollback(savepoint)
            } catch (rollbackFailure: Throwable) {
                try { connection.close() } catch (closeFailure: Throwable) { rollbackFailure.addSuppressed(closeFailure) }
                throw rollbackFailure
            }
            connection.releaseSavepoint(savepoint)
        } catch (cleanupFailure: Throwable) {
            if (failure == null) throw cleanupFailure
            failure.addSuppressed(cleanupFailure)
        } finally {
            currentDialectMetadata.resetCaches()
        }
    }

    val blocked = mutableMapOf<String, DestructivePostgresMigrationChange>()
    if (nonDestructive) {
        drops.filter { existing.getValue(it).materialized }.forEach {
            blocked[it] = DestructivePostgresMigrationChange.DROP_MATERIALIZED_VIEW
        }
        // A base view cannot be dropped when one of its dependents must remain in place.
        var changed: Boolean
        do {
            changed = false
            (dependencies.keys + desiredDependencies.keys).forEach { view ->
                val sources = dependencies[view].orEmpty() + desiredDependencies[view].orEmpty()
                sources.forEach { source ->
                    if (view in drops && source in drops) {
                        val reason = blocked[view] ?: blocked[source]
                        if (reason != null) {
                            if (blocked.putIfAbsent(view, reason) == null) changed = true
                            if (blocked.putIfAbsent(source, reason) == null) changed = true
                        }
                    }
                    if (view in creates && source in blocked && view !in blocked) {
                        blocked[view] = blocked.getValue(source)
                        changed = true
                    }
                }
            }
        } while (changed)
    }
    val suppressed = mutableListOf<SuppressedPostgresMigrationStatement>()
    fun include(name: String, statement: PostgresMigrationStatement): Boolean {
        val reason = blocked[name] ?: return true
        suppressed += SuppressedPostgresMigrationStatement(statement, reason)
        return false
    }
    val dropStatements = existingOrder.asReversed().filter { it in drops }.mapNotNull { name ->
        existing.getValue(name).drop(schema.schemaName).takeIf { include(name, it) }
    }
    val createStatements = orderViews(creates.keys, desiredDependencies).mapNotNull { name ->
        creates.getValue(name).takeIf { include(name, it) }
    }
    if (nonDestructive) removed.filter { it !in drops }.forEach { name ->
        val drop = existing.getValue(name).drop(schema.schemaName)
        suppressed += SuppressedPostgresMigrationStatement(drop, drop.destructiveChange!!)
    }
    return ViewMigrationPlan(dropStatements, createStatements, suppressed)
}

private fun ColumnDefinition.viewShape() = copy(
    nullable = true, default = null, identity = null, generated = null,
)

private data class ViewShape(val query: String, val columns: List<List<String?>>)
private data class ExistingView(val name: String, val materialized: Boolean, val definition: ViewShape) {
    fun drop(schema: String): PostgresMigrationStatement = if (materialized) DropMaterializedView(QualifiedName(name, schema))
        else DropView(QualifiedName(name, schema))
}

private fun readViews(connection: Connection, schema: String): Map<String, ExistingView> {
    val names = connection.prepareStatement("""
        SELECT c.relname, c.relkind = 'm'
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = ? AND c.relkind IN ('v', 'm')
          AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass
              AND d.objid = c.oid AND d.refclassid = 'pg_extension'::regclass AND d.deptype = 'e')
    """.trimIndent()).use { statement ->
        statement.setString(1, schema)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getBoolean(2)) } }
    }
    return names.associate { (name, materialized) -> name to ExistingView(name, materialized,
        readViewShape(connection, QualifiedName(name, schema).toSql())) }
}

private fun readViewDependencies(connection: Connection, schema: String): Map<String, Set<String>> =
    connection.prepareStatement("""
        SELECT DISTINCT v.relname, source.relname
        FROM pg_rewrite r JOIN pg_class v ON v.oid = r.ev_class
        JOIN pg_namespace vn ON vn.oid = v.relnamespace
        JOIN pg_depend d ON d.classid = 'pg_rewrite'::regclass AND d.objid = r.oid
        JOIN pg_class source ON d.refclassid = 'pg_class'::regclass AND source.oid = d.refobjid
        JOIN pg_namespace sn ON sn.oid = source.relnamespace
        WHERE vn.nspname = ? AND sn.nspname = ? AND v.relkind IN ('v', 'm') AND v.oid <> source.oid
    """.trimIndent()).use { statement ->
        statement.setString(1, schema)
        statement.setString(2, schema)
        statement.executeQuery().use { rows ->
            val result = mutableMapOf<String, MutableSet<String>>()
            while (rows.next()) result.getOrPut(rows.getString(1)) { mutableSetOf() } += rows.getString(2)
            result
        }
    }

private fun orderViews(names: Set<String>, dependencies: Map<String, Set<String>>): List<String> {
    val visited = mutableSetOf<String>()
    val visiting = mutableSetOf<String>()
    val ordered = mutableListOf<String>()
    fun visit(name: String) {
        if (name in visited) return
        require(visiting.add(name)) { "Cyclic view dependency involving $name" }
        dependencies[name].orEmpty().filter { it in names }.forEach(::visit)
        visiting -= name
        visited += name
        ordered += name
    }
    names.forEach(::visit)
    return ordered
}

private fun canonicalizeQuery(connection: Connection, query: String): ViewShape {
    val name = QualifiedName("keep_view_${UUID.randomUUID().toString().replace("-", "")}", "pg_temp")
    connection.executeViewSql("CREATE TEMPORARY VIEW ${quoteIdentifier(name.name)} AS $query")
    return readViewShape(connection, name.toSql()).also { connection.executeViewSql(DropView(name).toSql()) }
}

private fun readViewShape(connection: Connection, name: String): ViewShape {
    val query = connection.prepareStatement("SELECT pg_get_viewdef(?::regclass, false)").use { statement ->
        statement.setString(1, name)
        statement.executeQuery().use { rows -> check(rows.next()); rows.getString(1).trim().trimEnd(';') }
    }
    val columns = connection.prepareStatement("""
        SELECT attname, format_type(atttypid, atttypmod), attcollation::text
        FROM pg_attribute WHERE attrelid = ?::regclass AND attnum > 0 AND NOT attisdropped ORDER BY attnum
    """.trimIndent()).use { statement ->
        statement.setString(1, name)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add((1..3).map { rows.getString(it) }) } }
    }
    return ViewShape(query, columns)
}

private fun Connection.executeViewSql(sql: String) { createStatement().use { it.execute(sql) } }
