package org.tekfive.keep.schema

/** A PostgreSQL view and the SELECT query that defines it. */
data class PostgresViewDefinition(
    val name: String,
    val query: String,
    val materialized: Boolean = false,
)
