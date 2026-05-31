package org.tekfive.keep.job.jobs

import org.junit.jupiter.api.Test
import org.tekfive.keep.job.schedule.FixedIntervalJobSpec
import org.tekfive.jfk.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class CleanJobsJobRecordTest {

    @Test
    fun `companion object jobTypeIdentifier is clean-jobs`() {
        assertEquals("clean-jobs", CleanJobsJob.jobTypeIdentifier)
    }

    @Test
    fun `companion object maxRetries is 0`() {
        assertEquals(0, CleanJobsJob.maxRetriesOnFailure)
    }

    @Test
    fun `companion object minSecondsBetweenRetries is null`() {
        assertNull(CleanJobsJob.minSecondsBetweenRetries)
    }

    @Test
    fun `companion object retryExceptionBaseTypes is empty`() {
        assertTrue(CleanJobsJob.retryExceptionBaseTypes.isEmpty())
    }

    @Test
    fun `companion object estimateRuntime is false`() {
        assertFalse(CleanJobsJob.estimateRuntime)
    }

    @Test
    fun `companion object jobPriority is null`() {
        assertNull(CleanJobsJob.jobPriority)
    }

    @Test
    fun `companion object implements FixedIntervalJobSpec`() {
        val spec: Any = CleanJobsJob
        assertTrue(spec is FixedIntervalJobSpec)
    }

    @Test
    fun `companion object intervalSeconds defaults to 24 hours`() {
        // Default scheduleIntervalHours is 24
        val expectedSeconds = 24.hours.inWholeSeconds
        assertEquals(expectedSeconds, CleanJobsJob.intervalSeconds)
    }

    @Test
    fun `companion object getEstimatedRuntimeQueries returns empty list`() {
        val queries = CleanJobsJob.getEstimatedRuntimeQueries(JsonObject(emptyMap()))
        assertTrue(queries.isEmpty())
    }

    @Test
    fun `createExecutor returns a CleanJobsJob instance`() {
        val executor = CleanJobsJob.createJob()
        assertTrue(executor is CleanJobsJob)
    }

    @Test
    fun `schedule with lastEndedAt returns job with correct minimumStartAt`() {
        val lastEndedAt = 1_000_000_000L
        val job = CleanJobsJob.schedule(lastEndedAt)
        assertEquals(lastEndedAt + CleanJobsJob.intervalSeconds * 1000L, job.minimumStartAt)
    }

    @Test
    fun `schedule with null lastEndedAt returns job starting from now plus interval`() {
        val before = System.currentTimeMillis()
        val job = CleanJobsJob.schedule(null)
        val after = System.currentTimeMillis()

        val minStart = job.minimumStartAt!!
        val intervalMillis = CleanJobsJob.intervalSeconds * 1000L
        assertTrue(minStart >= before + intervalMillis)
        assertTrue(minStart <= after + intervalMillis)
    }
}
