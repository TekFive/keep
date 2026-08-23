package org.tekfive.keep.data

import org.tekfive.keep.schema.PostgresSchemaObject

/** Optional SQL hooks applied by [org.tekfive.keep.schema.AppSchema] around table creation. */
interface DataTableSchemaHooks {
    /** Typed PostgreSQL objects created after this table and its indexes. */
    val postgresObjects: List<PostgresSchemaObject>
        get() = emptyList()

    val postSchemaCreateSql: List<String>

    val customTypes: List<String>

    val customIndices: List<String>
}
