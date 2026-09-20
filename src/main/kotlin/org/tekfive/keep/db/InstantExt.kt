package org.tekfive.keep.db

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Instant

/**
 * Returns PostgreSQL's start timestamp for the current transaction, or [Instant.now] outside one.
 * The receiver's value is ignored. Works with both KEEP [db] and Exposed JDBC transactions.
 * Reads the database timestamp on each call so a manual commit or rollback starts a fresh value.
 */
fun Instant.transaction(): Instant {
    val transaction = TransactionManager.currentOrNull() ?: return Instant.now()
    return checkNotNull(transaction.exec("SELECT transaction_timestamp()") { result ->
        check(result.next()) { "PostgreSQL did not return the transaction timestamp" }
        result.getTimestamp(1).toInstant()
    }) { "PostgreSQL did not return the transaction timestamp" }
}
