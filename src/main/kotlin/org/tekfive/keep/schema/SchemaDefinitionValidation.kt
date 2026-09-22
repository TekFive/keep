package org.tekfive.keep.schema

/** Names of shared types and indexes must be unique across the schema, not just each table. */
internal fun KeepSchema.validateSharedDefinitions() {
    require(types.map { it.name }.distinct().size == types.size) { "Duplicate schema type names" }
    val indexes = declaredPostgresObjects.filterIsInstance<PostgresIndexDefinition>()
    val uniqueKeys = declaredPostgresObjects.filterIsInstance<PostgresUniqueConstraintDefinition>()
    val indexNames = indexes.map { it.name } + uniqueKeys.map { it.name } + tables.flatMap { it.indices.map { index -> index.indexName } }
    require(indexNames.distinct().size == indexNames.size) { "Duplicate index names in schema $schemaName" }
    val relations = tables.map { it.tableName.substringAfterLast('.').removeSurrounding("\"") } + views.map { it.name } + declaredSequenceNames
    require(indexNames.none { it in relations }) { "Indexes cannot share names with tables, views, or sequences" }
    require(types.none { it.name in relations.take(tables.size + views.size) }) { "Types cannot share names with tables or views" }
}
