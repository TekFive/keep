package org.tekfive.keep.lock

import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

/** One acquisition, identified by its owner token and monotonically increasing per-key generation. */
class LockLease internal constructor(
    private val table: LeaseLockTable,
    val key: String,
    val ownerToken: UUID,
    val fencingToken: Long,
    val leaseFor: Duration,
    val acquiredAt: Instant,
    expiresAt: Instant,
    val deadlineAt: Instant?,
) : AutoCloseable {
    /** Last confirmed expiry; a snapshot, not proof that ownership is still valid. */
    @Volatile
    var expiresAt: Instant = expiresAt
        private set

    /**
     * Renews a still-valid acquisition and returns the committed expiry.
     * Throws [LeaseLostException] after expiry, release, or takeover. Database failures propagate;
     * callers must stop protected work until ownership is confirmed. No automatic renewal occurs.
     */
    @Synchronized
    fun checkIn(): Instant = table.checkIn(this).also { expiresAt = it }

    /** Clears only this acquisition, even if expired. False means it was already released or replaced. */
    @Synchronized
    fun release(): Boolean = table.release(this)

    /** Releases this acquisition; a stale handle cannot release a successor. Database failures propagate. */
    override fun close() {
        release()
    }
}

class LeaseLostException(val key: String) : IllegalStateException("Lease ownership has been lost for '$key'.")

class LockAcquireTimeoutException(val key: String, val waitTimeout: Duration) :
    IllegalStateException("Timed out acquiring lease '$key' after $waitTimeout.")
