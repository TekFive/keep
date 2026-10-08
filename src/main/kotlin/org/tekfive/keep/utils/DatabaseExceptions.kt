package org.tekfive.keep.utils

import java.io.IOException
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLNonTransientException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** Whether this exception or its cause/JDBC next-exception chain contains a [SQLException]. */
fun Throwable.isDatabaseException(): Boolean = databaseExceptionChain().any { it is SQLException }

/**
 * Whether this failure indicates a potentially recoverable database/infrastructure problem.
 *
 * Recognizes JDBC transient/recoverable/connection exceptions, database-caused [IOException]s,
 * PostgreSQL connection/resource failures, shutdowns, session/transaction timeouts, serialization
 * failures, deadlocks, lock contention and server I/O errors. Bare I/O errors are not database errors.
 * Causes and JDBC next-exceptions are inspected, including wrappers such as ExposedSQLException.
 * Suppressed exceptions (often cleanup failures) are deliberately ignored; cycles are safe.
 *
 * Explicit non-recoverable SQLSTATEs or JDBC non-transient errors anywhere in that chain take
 * precedence. Missing SQLSTATEs and the generic wrapper state HY000 defer to types/causes; unknown
 * errors alone return false. In particular, data/constraint/schema/authentication errors, invalid
 * transaction states and query cancellation are not considered recoverable.
 *
 * This is a classification, not permission to replay a write: recovery may require a new connection
 * and retrying the entire transaction, and a connection failure can leave the commit outcome unknown.
 * The job subsystem's broader retry policy is separate.
 */
fun Throwable.isRecoverableDatabaseException(): Boolean {
    val chain = databaseExceptionChain().toList()
    if (chain.any { it is InterruptedException || it is CancellationException }) return false

    var recoverable = false
    for (exception in chain.filterIsInstance<SQLException>()) {
        if (exception is SQLNonTransientException && exception !is SQLNonTransientConnectionException) {
            return false
        }

        val state = exception.sqlState
        if (!state.isNullOrBlank() && state != "HY000") {
            if (!state.isRecoverableDatabaseState()) return false
            recoverable = true
        } else if (exception is SQLTransientException ||
            exception is SQLRecoverableException ||
            exception is SQLNonTransientConnectionException ||
            exception.databaseExceptionChain().any { it is IOException }
        ) {
            recoverable = true
        }
    }
    return recoverable
}

private fun String.isRecoverableDatabaseState(): Boolean =
    startsWith("08") || // Connection exceptions (including unknown transaction outcome).
        startsWith("53") || // Insufficient resources; recovery may require operator intervention.
        this in recoverableDatabaseStates

private val recoverableDatabaseStates = setOf(
    "40001", // Serialization failure.
    "40P01", // Deadlock detected.
    "55P03", // Lock not available.
    "57P01", // Administrative shutdown.
    "57P02", // Crash shutdown.
    "57P03", // Cannot connect now.
    "57P05", // Idle session timeout.
    "25P03", // Idle-in-transaction session timeout.
    "25P04", // Transaction timeout.
    "58030", // Server I/O error.
)

private fun Throwable.databaseExceptionChain(): Sequence<Throwable> = sequence {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Throwable>()
    pending.add(this@databaseExceptionChain)
    while (pending.isNotEmpty()) {
        val exception = pending.removeFirst()
        if (!visited.add(exception)) continue
        yield(exception)
        exception.cause?.let(pending::addLast)
        if (exception is SQLException) exception.nextException?.let(pending::addLast)
    }
}
