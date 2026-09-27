package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table

/** A standalone definition or a mapped view that owns its schema definition. */
interface PostgresView {
    val viewDefinition: PostgresViewDefinition
}

/**
 * A view defined by SQL or an Exposed query. [references] lists every source table/view,
 * including sources in joins, subqueries, and each UNION branch. View references determine
 * creation order; they must also be declared in [KeepSchema.views].
 */
data class PostgresViewDefinition(
    val name: String,
    val query: String = "",
    val materialized: Boolean = false,
    val references: List<Table> = emptyList(),
    val queryBuilder: (() -> AbstractQuery<*>)? = null,
) : PostgresView {
    override val viewDefinition: PostgresViewDefinition get() = this

    init {
        require(name.isNotBlank() && '.' !in name && '\u0000' !in name && name.toByteArray().size <= 63) {
            "View names must be unqualified PostgreSQL identifiers of at most 63 bytes"
        }
        require((query.isNotBlank()) != (queryBuilder != null)) {
            "View $name must define either SQL or an Exposed query builder"
        }
    }

    /** Renders literal values, never JDBC placeholders; requires a PostgreSQL rendering context. */
    fun renderQuery(): String {
        val built = queryBuilder?.invoke()
        require(built == null || built.targets.all { it in references }) {
            "View $name must list every query source in references, including all UNION branches"
        }
        return (built?.prepareSQL(QueryBuilder(false)) ?: query).trim().trimEnd(';').trimEnd()
    }
}

/** Defines a view using Exposed SELECT, JOIN, UNION, or UNION ALL queries. */
fun postgresView(
    name: String,
    vararg references: Table,
    materialized: Boolean = false,
    query: () -> AbstractQuery<*>,
): PostgresViewDefinition = PostgresViewDefinition(
    name, materialized = materialized, references = references.toList(), queryBuilder = query,
)

/** Defines the database view read through this Table, DataView, or UuidDataView mapping. */
fun Table.view(
    vararg references: Table,
    materialized: Boolean = false,
    query: () -> AbstractQuery<*>,
): PostgresViewDefinition = postgresView(
    tableName.substringAfterLast('.').removeSurrounding("\""), *references, materialized = materialized, query = query,
)

/** Validates sources and sorts views without requiring a database connection. */
internal fun KeepSchema.orderedViews(): List<PostgresViewDefinition> {
    val definitions = declaredViews
    val byName = definitions.associateBy { it.name }
    require(byName.size == definitions.size) { "Duplicate view names in schema $schemaName" }
    val visited = mutableSetOf<String>()
    val visiting = mutableSetOf<String>()
    val result = mutableListOf<PostgresViewDefinition>()
    fun visit(view: PostgresViewDefinition) {
        if (view.name in visited) return
        require(visiting.add(view.name)) { "Cyclic view dependency involving ${view.name}" }
        view.references.forEach { source ->
            val sourceSchema = source.schemaName?.removeSurrounding("\"")
            require(sourceSchema == null || sourceSchema == schemaName) {
                "View ${view.name} references ${source.tableName} outside schema $schemaName"
            }
            if (source !in tables) {
                val dependency = byName[source.tableName.substringAfterLast('.').removeSurrounding("\"")]
                require(dependency != null) { "View ${view.name} references undeclared table/view ${source.tableName}" }
                visit(dependency)
            }
        }
        visiting.remove(view.name)
        visited += view.name
        result += view
    }
    definitions.forEach(::visit)
    return result
}
