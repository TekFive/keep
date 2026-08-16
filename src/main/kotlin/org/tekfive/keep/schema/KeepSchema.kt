package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.core.Table

/**
 * The application-owned PostgreSQL objects that KEEP should manage.
 *
 * Tables, views, materialized views, and standalone sequences declared here form the authoritative
 * desired state for [schemaName]. Views must be listed in dependency order so base views are
 * created before views that reference them.
 */
abstract class KeepSchema(
    val schemaName: String = "public",
) {
    abstract val tables: List<Table>

    open val views: List<PostgresViewDefinition> = emptyList()

    open val sequenceNames: List<String> = emptyList()
}
