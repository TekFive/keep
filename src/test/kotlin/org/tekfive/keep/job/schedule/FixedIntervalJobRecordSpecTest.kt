package org.tekfive.keep.job.schedule

import org.junit.jupiter.api.Test
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobResult
import org.tekfive.keep.job.JobState
import org.tekfive.keep.job.JobCompleted
import org.tekfive.keep.job.db.QueryNode
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FixedIntervalJobRecordSpecTest {

    private fun createSpec(
        intervalSeconds: Long,
        runImmediatelyOnFirstSchedule: Boolean = false,
    ): FixedIntervalJobSpec {
        return object : FixedIntervalJobSpec {
            override val intervalSeconds: Long = intervalSeconds
            override val runImmediatelyOnFirstSchedule: Boolean = runImmediatelyOnFirstSchedule
            override val estimateRuntime: Boolean = false
            override val jobTypeIdentifier: String = "fixed-interval-test"
            override val jobPriority: Int? = null
            override val maxRetriesOnFailure: Int = 0
            override val minSecondsBetweenRetries: Int? = null
            override val retryExceptionBaseTypes: List<KClass<out Exception>> = emptyList()
            override fun getEstimatedRuntimeQueries(jobDetails: JsonObject): List<QueryNode> = emptyList()
            override fun createJob(): Job = object : Job {
                override fun execute(context: JobContext): JobResult = JobCompleted()
            }
        }
    }

    @Test
    fun `schedule with lastEndedAt returns job starting after interval`() {
        val intervalSeconds = 60L // 1 minute
        val lastEndedAt = 1_000_000L
        val spec = createSpec(intervalSeconds)

        val job = spec.schedule(lastEndedAt)

        assertNotNull(job)
        assertEquals(lastEndedAt + intervalSeconds * 1000L, job.minimumStartAt)
    }

    @Test
    fun `schedule with null lastEndedAt uses current time plus interval`() {
        val intervalSeconds = 30L
        val spec = createSpec(intervalSeconds)

        val before = System.currentTimeMillis()
        val job = spec.schedule(null)
        val after = System.currentTimeMillis()

        assertNotNull(job)
        val minStart = job.minimumStartAt!!
        val intervalMillis = intervalSeconds * 1000L
        assertTrue(minStart >= before + intervalMillis, "minimumStartAt ($minStart) should be >= before + interval (${before + intervalMillis})")
        assertTrue(minStart <= after + intervalMillis, "minimumStartAt ($minStart) should be <= after + interval (${after + intervalMillis})")
    }

    @Test
    fun `schedule with null lastEndedAt can run immediately when configured`() {
        val spec = createSpec(intervalSeconds = 30L, runImmediatelyOnFirstSchedule = true)

        val job = spec.schedule(null)

        assertNotNull(job)
        assertEquals(null, job.minimumStartAt)
    }

    @Test
    fun `schedule sets job type from spec`() {
        val spec = createSpec(60L)
        val job = spec.schedule(1_000_000L)

        assertNotNull(job)
        assertEquals("fixed-interval-test", job.type)
    }

    @Test
    fun `schedule sets job state to WAITING_FOR_START`() {
        val spec = createSpec(60L)
        val job = spec.schedule(1_000_000L)

        assertNotNull(job)
        assertEquals(JobState.PENDING, job.state)
    }

    @Test
    fun `schedule with large interval computes correct minimumStartAt`() {
        val oneDay = 86_400L
        val lastEndedAt = 1_000_000_000L
        val spec = createSpec(oneDay)

        val job = spec.schedule(lastEndedAt)

        assertNotNull(job)
        assertEquals(lastEndedAt + oneDay * 1000L, job.minimumStartAt)
    }

    @Test
    fun `schedule with small interval computes correct minimumStartAt`() {
        val oneSecond = 1L
        val lastEndedAt = 500_000L
        val spec = createSpec(oneSecond)

        val job = spec.schedule(lastEndedAt)

        assertNotNull(job)
        assertEquals(lastEndedAt + oneSecond * 1000L, job.minimumStartAt)
    }

    @Test
    fun `schedule always returns non-null job`() {
        val spec = createSpec(60L)

        // With lastEndedAt
        assertNotNull(spec.schedule(1_000_000L))
        // Without lastEndedAt
        assertNotNull(spec.schedule(null))
    }
}
