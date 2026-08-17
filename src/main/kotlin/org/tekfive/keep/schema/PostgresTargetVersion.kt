package org.tekfive.keep.schema

/** PostgreSQL version used for deterministic, connection-free DDL decisions. */
data class PostgresTargetVersion(
    val major: Int = 16,
    val minor: Int = 0,
) {
    init {
        require(major >= 12) { "KEEP fresh-install generation supports PostgreSQL 12 or newer" }
        require(minor >= 0) { "PostgreSQL minor version must not be negative" }
    }
}
