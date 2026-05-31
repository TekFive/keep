package org.tekfive.keep.migration

import org.jetbrains.exposed.v1.core.Table

/**
 * Records which migrations have been applied to this database. Managed
 * exclusively by [MigrationRunner]; do not write to it from application
 * code.
 *
 * The primary key is [version]; attempting to insert a duplicate row
 * yields a SQL unique-constraint violation, which the runner relies on as
 * a last-line-of-defense against concurrent peers racing past the
 * advisory lock.
 */
object MigrationHistoryTable : Table("keep_schema_migrations") {

    val version = long("version")

    val name = text("name")

    /** Epoch milliseconds when this migration was applied. */
    val appliedAt = long("applied_at")

    val appliedBy = text("applied_by")

    override val primaryKey = PrimaryKey(version)
}
