package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.data.DataTableSchema
import org.tekfive.keep.data.TypedDataTuple

/**
 * The application-owned PostgreSQL objects that KEEP should manage.
 *
 * Tables, extensions, views, materialized views, standalone sequences, and ordered SQL hooks
 * declared here form the authoritative desired state for [schemaName]. Views must be listed in
 * dependency order so base views are created before views that reference them.
 */
abstract class KeepSchema(
    val schemaName: String = "public",
) {
    abstract val tables: List<Table>

    /** PostgreSQL extensions required before types and tables are created, e.g. [CITEXT] or [POSTGIS]. */
    open val extensions: List<String> = emptyList()

    /** Schema-owned types shared by any number of tables. */
    open val types: List<PostgresTypeDefinition> = emptyList()

    open val views: List<PostgresViewDefinition> = emptyList()

    open val sequenceNames: List<String> = emptyList()

    /** Rich sequence definitions. Name-only entries are derived from [sequenceNames]. */
    open val sequenceDefinitions: List<PostgresSequenceDefinition>
        get() = sequenceNames.map(::PostgresSequenceDefinition)

    /** Effective sequence names used by fresh-install and merge generators. */
    val declaredSequenceNames: List<String>
        get() = sequenceDefinitions.map { it.name }

    /** SQL emitted after extensions but before sequences and table creation. */
    open val beforeTablesSql: List<String> = emptyList()

    /** SQL emitted after tables, constraints, and indexes but before views. */
    open val afterTablesSql: List<String> = emptyList()

    /** Schema-level PostgreSQL objects. Table-owned objects can also be declared through table hooks. */
    open val postgresObjects: List<PostgresTableObject> = emptyList()

    /** Every first-class PostgreSQL object declared by the schema or one of its tables. */
    val declaredPostgresObjects: List<PostgresTableObject>
        get() = postgresObjects +
            tables.filterIsInstance<DataTableSchema>().flatMap { it.postgresObjects } +
            tables.filterIsInstance<TypedDataTuple<*, *>>().flatMap { it.columnPostgresObjects }

    /** Well-known extension names for [extensions]; custom names can also be supplied. */
    companion object {
        const val BTREE_GIN = "btree_gin"
        const val BTREE_GIST = "btree_gist"
        const val CITEXT = "citext"
        const val HSTORE = "hstore"
        const val LTREE = "ltree"
        const val PGCRYPTO = "pgcrypto"
        const val PG_STAT_STATEMENTS = "pg_stat_statements"
        const val PG_TRGM = "pg_trgm"
        const val POSTGIS = "postgis"
        const val TABLEFUNC = "tablefunc"
        const val UNACCENT = "unaccent"
        const val UUID_OSSP = "uuid-ossp"
    }
}
