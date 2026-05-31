package org.tekfive.keep.job.db

import org.tekfive.ack.Ack
import java.util.LinkedList
import kotlin.math.pow

interface DatabaseRetrySettings {
    val windowMillis: Long
    val baseDelaySeconds: Double
    val maxDelaySeconds: Double
}

class DefaultDatabaseRetrySettings(
    override val windowMillis: Long = 60_000,
    override val baseDelaySeconds: Double = 2.0,
    override val maxDelaySeconds: Double = 60.0,
) : DatabaseRetrySettings {}



class DatabaseRetryPolicy(
    private val settings: DatabaseRetrySettings = DefaultDatabaseRetrySettings()
) {
    private val errorTimestamps = LinkedList<Long>()

    val currentWaitSeconds: Long
        get() {
            synchronized(this) {
                pruneOldErrors(System.currentTimeMillis())
                if (errorTimestamps.isEmpty()) return 0

                val exponent = (errorTimestamps.size - 1).toDouble()
                return (settings.baseDelaySeconds * 2.0.pow(exponent)).toLong()
                    .coerceAtMost(settings.maxDelaySeconds.toLong())
            }
        }

    constructor(windowMillis: Long, baseDelaySeconds: Double, maxDelaySeconds: Double) : this(DefaultDatabaseRetrySettings(windowMillis, baseDelaySeconds, maxDelaySeconds))

    /**
     * Call this whenever a SQLException occurs.
     * Returns the number of seconds to wait before the next attempt.
     */
    @Synchronized
    fun getWaitTimeAndRecordError(): Long {
        val now = System.currentTimeMillis()
        
        // 1. Record the new error
        errorTimestamps.add(now)

        // 2. Remove errors outside the rolling window
        pruneOldErrors(now)

        // 3. Calculate exponential backoff based on window count
        // Wait = baseDelay * (2 ^ (errorCount - 1))
        val exponent = (errorTimestamps.size - 1).toDouble()
        val calculatedWait = (settings.baseDelaySeconds * 2.0.pow(exponent)).toLong()

        return calculatedWait.coerceAtMost(settings.maxDelaySeconds.toLong())
    }

    private fun pruneOldErrors(now: Long) {
        val windowStart = now - settings.windowMillis
        while (errorTimestamps.isNotEmpty() && errorTimestamps.peek() < windowStart) {
            errorTimestamps.removeFirst()
        }
    }
}
