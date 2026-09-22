package org.tekfive.keep.lock

import org.tekfive.keep.db.dbConnection

/**
 * Named PostgreSQL advisory locks held until the current transaction commits or rolls back.
 * Both methods require an active KEEP or Exposed JDBC transaction and allow reacquisition by
 * the owning transaction. No table or explicit unlock is needed.
 *
 * Keys use PostgreSQL's hashtextextended(key, 0); use application-specific prefixes to separate
 * unrelated resources. Hash collisions can cause unrelated keys to contend for the same lock.
 */
object TransactionLocks {
    /** Returns immediately with false when another transaction holds the lock. */
    fun tryAcquire(key: String): Boolean {
        dbConnection().prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { result ->
                check(result.next()) { "PostgreSQL did not return an advisory lock result" }
                return result.getBoolean(1)
            }
        }
    }

    /** Waits for the lock, subject to the transaction's PostgreSQL lock and statement timeouts. */
    fun acquire(key: String) {
        dbConnection().prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().close()
        }
    }
}
