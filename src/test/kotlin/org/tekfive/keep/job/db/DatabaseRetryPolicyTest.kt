package org.tekfive.keep.job.db

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DatabaseRetryPolicyTest {

    @Test
    fun `first error returns base delay`() {
        val policy = DatabaseRetryPolicy(DefaultDatabaseRetrySettings(baseDelaySeconds = 2.0, maxDelaySeconds = 60.0))
        val wait = policy.getWaitTimeAndRecordError()
        assertEquals(2L, wait)
    }

    @Test
    fun `successive errors increase exponentially`() {
        val policy = DatabaseRetryPolicy(
            windowMillis = 60_000,
            baseDelaySeconds = 2.0,
            maxDelaySeconds = 60.0
        )

        assertEquals(2L, policy.getWaitTimeAndRecordError())   // 2 * 2^0 = 2
        assertEquals(4L, policy.getWaitTimeAndRecordError())   // 2 * 2^1 = 4
        assertEquals(8L, policy.getWaitTimeAndRecordError())   // 2 * 2^2 = 8
        assertEquals(16L, policy.getWaitTimeAndRecordError())  // 2 * 2^3 = 16
    }

    @Test
    fun `delay is capped at max delay`() {
        val policy = DatabaseRetryPolicy(
            windowMillis = 60_000,
            baseDelaySeconds = 2.0,
            maxDelaySeconds = 10.0
        )

        policy.getWaitTimeAndRecordError() // 2
        policy.getWaitTimeAndRecordError() // 4
        policy.getWaitTimeAndRecordError() // 8
        val wait = policy.getWaitTimeAndRecordError() // would be 16, capped at 10
        assertEquals(10L, wait)
    }

    @Test
    fun `currentWaitSeconds is zero with no errors`() {
        val policy = DatabaseRetryPolicy()
        assertEquals(0L, policy.currentWaitSeconds)
    }

    @Test
    fun `currentWaitSeconds reflects error count`() {
        val policy = DatabaseRetryPolicy(DefaultDatabaseRetrySettings(baseDelaySeconds = 2.0, maxDelaySeconds = 60.0))
        policy.getWaitTimeAndRecordError()
        policy.getWaitTimeAndRecordError()
        // After 2 errors: 2 * 2^(2-1) = 4
        assertEquals(4L, policy.currentWaitSeconds)
    }
}
