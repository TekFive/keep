package org.tekfive.keep.job.db

import org.tekfive.keep.job.JobConfiguration
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicLong

internal class DatabaseGatekeeper(
    private val configuration: JobConfiguration
) {
    private val retryAt = AtomicLong(0L)

    operator fun <T> invoke(action:() -> T): T {
        while (true) {
            val now = System.currentTimeMillis()
            val waitUntil = retryAt.get()

            val sleepMSecs = waitUntil - now
            if (sleepMSecs > 0) {
                Thread.sleep(sleepMSecs)
            } else {
                break
            }
        }

        return action()
    }

    fun keepTrying(action: () -> Unit) {
        while (true) {
            try {
                return this { action() }
            } catch (e: SQLException) {
                if (!onSQLException(e)) {
                    throw e
                }
            }
        }
    }

    fun onSQLException(sqlException: SQLException): Boolean {
        if (!isRecoverable(sqlException)) {
            return false
        }

        val backoffSeconds = configuration.getDatabaseBackoffSeconds(sqlException)
        val newRetryAt = System.currentTimeMillis() + (backoffSeconds * 1000L)
        retryAt.updateAndGet { currentRetryAt -> newRetryAt.coerceAtLeast(currentRetryAt) }

        return true
    }

    fun isRecoverable(sqlException: SQLException): Boolean {
        val sqlState = sqlException.sqlState
        return sqlState != null && (sqlState.startsWith("08") || // Class 08 — Connection Exceptions
            sqlState.startsWith("25") || // Class 25 — Invalid Transaction State
            sqlState.startsWith("40") || // Class 40 — Transaction Rollback
            sqlState.startsWith("53") || // Class 53 — Insufficient Resources
            sqlState.startsWith("57")) // Class 57 — Operator Intervention
    }
}
