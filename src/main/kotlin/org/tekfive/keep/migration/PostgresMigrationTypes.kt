package org.tekfive.keep.migration

/** A statement omitted from a non-destructive migration plan. */
data class SuppressedPostgresMigrationStatement(
    val sql: String,
    val reason: DestructivePostgresMigrationChange,
)

/** Operations that KEEP conservatively considers capable of losing stored data or schema objects. */
enum class DestructivePostgresMigrationChange {
    DROP_TABLE,
    DROP_VIEW,
    DROP_MATERIALIZED_VIEW,
    DROP_SEQUENCE,
    DROP_COLUMN,
    ALTER_COLUMN_TYPE,
    DELETE_DATA,
    TRUNCATE_DATA,
    DROP_SCHEMA,
    DROP_TYPE,
    DROP_EXTENSION,
}
