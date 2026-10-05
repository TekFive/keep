package org.tekfive.keep.lock

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.tekfive.keep.data.instant
import org.tekfive.keep.db.DbConnection
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Persistent, case-sensitive leases that do not reserve a connection while work runs.
 * Add this table to the application's schema before use. Qualify [name] for non-public schemas.
 * Every operation commits on its own connection, independently of any caller transaction.
 *
 * [connectionProvider] must return a fresh, exclusively owned connection that this class may
 * commit and close. Its connection/login timeout must be configured separately; [operationTimeout]
 * bounds database statement and row-lock waits after connecting. Do not modify lease rows from
 * application transactions or delete them: their fencing generations must never be reset.
 *
 * Expiry permits takeover but cannot stop an old worker. Protected resources must enforce
 * [LockLease.fencingToken] to reject stale writes. Check-ins alone do not fence side effects.
 */
open class LeaseLockTable(
    name: String,
    val operationTimeout: Duration = 5.seconds,
    private val connectionProvider: () -> Connection = DbConnection::createConnection,
) : Table(name) {
    val lockKey = varchar("lock_key", 512)
    val ownerToken = javaUUID("owner_token").nullable()
    val fencingToken = long("fencing_token").default(0)
    val acquiredAt = instant("acquired_at").nullable()
    val lastCheckInAt = instant("last_checkin_at").nullable()
    val expiresAt = instant("expires_at").nullable()
    val deadlineAt = instant("deadline_at").nullable()
    override val primaryKey = PrimaryKey(lockKey)

    init {
        require(operationTimeout.isFinite() && operationTimeout.inWholeMilliseconds in 1..Int.MAX_VALUE.toLong()) {
            "operationTimeout must be between 1 millisecond and ${Int.MAX_VALUE} milliseconds"
        }
    }

    private val sqlName = tableName.split('.').joinToString(".") {
        "\"${it.removeSurrounding("\"").replace("\"", "\"\"")}\""
    }

    /**
     * Makes one attempt; null means an active owner or database row-lock contention.
     * Other database errors propagate. Reacquiring a key is not reentrant; renew the returned
     * handle instead. [maxLifetime], when supplied, caps ownership regardless of check-ins.
     */
    fun tryAcquire(key: String, leaseFor: Duration, maxLifetime: Duration? = null): LockLease? {
        validate(key, leaseFor, maxLifetime)
        return tryAcquire(key, leaseFor, maxLifetime, operationTimeout)
    }

    /**
     * Retries contention with jitter until [waitTimeout], then throws [LockAcquireTimeoutException].
     * No connection is held between attempts. The retry budget uses a monotonic clock; opening
     * a connection is additionally subject to the provider's timeout. Interruption propagates.
     */
    fun acquire(key: String, leaseFor: Duration, waitTimeout: Duration, maxLifetime: Duration? = null): LockLease {
        validate(key, leaseFor, maxLifetime)
        positiveMillis(waitTimeout, "waitTimeout")
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            val remaining = waitTimeout - started.elapsedNow()
            if (remaining < 1.milliseconds) throw LockAcquireTimeoutException(key, waitTimeout)
            tryAcquire(key, leaseFor, maxLifetime, minOf(operationTimeout, remaining))?.let { return it }
            val sleepMillis = minOf(
                (waitTimeout - started.elapsedNow()).inWholeMilliseconds,
                ThreadLocalRandom.current().nextLong(25, 76),
            )
            if (sleepMillis <= 0) throw LockAcquireTimeoutException(key, waitTimeout)
            Thread.sleep(sleepMillis)
        }
    }

    private fun tryAcquire(key: String, leaseFor: Duration, maxLifetime: Duration?, timeout: Duration): LockLease? {
        val token = UUID.randomUUID()
        return try {
            independently(timeout) { connection ->
                connection.prepareStatement("INSERT INTO $sqlName (lock_key) VALUES (?) ON CONFLICT (lock_key) DO NOTHING").use {
                    it.setString(1, key)
                    it.executeUpdate()
                }
                lockRow(connection, key)
                // Sample time after obtaining the row lock, never before a potentially contended wait.
                connection.prepareStatement(
                    """WITH tick AS MATERIALIZED (SELECT $NOW_MILLIS AS now)
                    UPDATE $sqlName SET owner_token = ?, fencing_token = fencing_token + 1,
                        acquired_at = tick.now, last_checkin_at = tick.now,
                        expires_at = tick.now + LEAST(?::bigint, ?::bigint),
                        deadline_at = tick.now + ?::bigint
                    FROM tick WHERE lock_key = ? AND (owner_token IS NULL OR expires_at <= tick.now)
                    RETURNING fencing_token, acquired_at, expires_at, deadline_at""".trimIndent(),
                ).use { statement ->
                    statement.setObject(1, token)
                    statement.setLong(2, leaseFor.inWholeMilliseconds)
                    statement.setNullableLong(3, maxLifetime?.inWholeMilliseconds)
                    statement.setNullableLong(4, maxLifetime?.inWholeMilliseconds)
                    statement.setString(5, key)
                    statement.executeQuery().use { result ->
                        if (!result.next()) null else LockLease(
                            this, key, token, result.getLong("fencing_token"), leaseFor,
                            Instant.ofEpochMilli(result.getLong("acquired_at")),
                            Instant.ofEpochMilli(result.getLong("expires_at")),
                            result.getLong("deadline_at").let { if (result.wasNull()) null else Instant.ofEpochMilli(it) },
                        )
                    }
                }
            }
        } catch (e: SQLException) {
            if (e.sqlState == "55P03") null else throw e
        }
    }

    internal fun checkIn(lease: LockLease): Instant = independently(operationTimeout) { connection ->
        lockRow(connection, lease.key)
        connection.prepareStatement(
            """WITH tick AS MATERIALIZED (SELECT $NOW_MILLIS AS now)
            UPDATE $sqlName SET last_checkin_at = tick.now,
                expires_at = LEAST(tick.now + ?::bigint, deadline_at)
            FROM tick WHERE lock_key = ? AND owner_token = ? AND fencing_token = ?
                AND expires_at > tick.now
            RETURNING expires_at""".trimIndent(),
        ).use { statement ->
            statement.setLong(1, lease.leaseFor.inWholeMilliseconds)
            statement.setString(2, lease.key)
            statement.setObject(3, lease.ownerToken)
            statement.setLong(4, lease.fencingToken)
            statement.executeQuery().use { result ->
                if (!result.next()) throw LeaseLostException(lease.key)
                Instant.ofEpochMilli(result.getLong(1))
            }
        }
    }

    internal fun release(lease: LockLease): Boolean = independently(operationTimeout) { connection ->
        connection.prepareStatement(
            "UPDATE $sqlName SET owner_token = NULL WHERE lock_key = ? AND owner_token = ? AND fencing_token = ?",
        ).use { statement ->
            statement.setString(1, lease.key)
            statement.setObject(2, lease.ownerToken)
            statement.setLong(3, lease.fencingToken)
            statement.executeUpdate() == 1
        }
    }

    private fun lockRow(connection: Connection, key: String) {
        connection.prepareStatement("SELECT lock_key FROM $sqlName WHERE lock_key = ? FOR UPDATE").use {
            it.setString(1, key)
            it.executeQuery().use { result -> result.next() }
        }
    }

    private fun <T> independently(timeout: Duration, block: (Connection) -> T): T {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Interrupted during lease operation")
        return connectionProvider().use { connection ->
            connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            try {
                connection.prepareStatement("SELECT set_config('lock_timeout', ?, true), set_config('statement_timeout', ?, true)").use {
                    val millis = timeout.inWholeMilliseconds.coerceAtLeast(1).toString()
                    it.setString(1, (timeout.inWholeMilliseconds / 2).coerceAtLeast(1).toString())
                    it.setString(2, millis)
                    it.executeQuery().close()
                }
                val result = block(connection)
                connection.commit()
                result
            } catch (failure: Throwable) {
                try {
                    connection.rollback()
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            }
        }
    }

    private fun validate(key: String, leaseFor: Duration, maxLifetime: Duration?) {
        require(key.isNotBlank() && key.length <= 512) { "key must contain 1 to 512 characters" }
        positiveMillis(leaseFor, "leaseFor")
        maxLifetime?.let { positiveMillis(it, "maxLifetime") }
    }

    private fun positiveMillis(duration: Duration, name: String) {
        require(duration.isFinite() && duration.inWholeMilliseconds > 0) { "$name must be finite and at least 1 millisecond" }
    }

    private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
    }

    private companion object {
        const val NOW_MILLIS = "floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint"
    }
}
