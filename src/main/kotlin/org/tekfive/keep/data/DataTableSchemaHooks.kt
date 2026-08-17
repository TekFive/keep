package org.tekfive.keep.data

/** Optional SQL hooks applied by [org.tekfive.keep.schema.AppSchema] around table creation. */
interface DataTableSchemaHooks {
    val postSchemaCreateSql: List<String>

    val customTypes: List<String>

    val customIndices: List<String>
}
