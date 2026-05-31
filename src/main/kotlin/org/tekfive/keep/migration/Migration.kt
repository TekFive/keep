package org.tekfive.keep.migration

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/**
 * A single database migration. Implementations are registered in an ordered
 * list and applied by [MigrationRunner] in ascending [version] order. Each
 * migration runs inside its own database transaction; if [apply] throws,
 * the transaction rolls back and the migration is NOT recorded as applied.
 *
 * Implementations must be effectively immutable and side-effect-free
 * outside the supplied [JdbcTransaction]. Once a migration has been applied
 * to any production database, its [version], [name], and behavior MUST NOT
 * be changed — write a new migration with a higher version to amend or fix.
 */
interface Migration {

    /** Unique, monotonically increasing version number. Must be > 0. */
    val version: Long

    /** Human-readable name. Stored in history for audit; not interpreted. */
    val name: String

    /**
     * Applies the migration within the supplied transaction. Use
     * [JdbcTransaction.exec] for raw DDL/SQL or any Exposed DSL operation
     * — both run inside the same open transaction.
     */
    fun apply(tx: JdbcTransaction)
}
