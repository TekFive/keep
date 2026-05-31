package org.tekfive.keep.job.schedule

import org.junit.jupiter.api.Test
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobResult
import org.tekfive.keep.job.JobState
import org.tekfive.keep.job.JobCompleted
import org.tekfive.keep.job.db.QueryNode
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CronJobRecordSpecTest {

    private val utc = ZoneId.of("UTC")

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, utc)
            .toInstant().toEpochMilli()
    }

    private fun createSpec(cronExpr: String, zone: ZoneId = utc): CronJobSpec {
        val parsed = CronExpression.parse(cronExpr)
        return object : CronJobSpec {
            override val cronExpression: CronExpression = parsed
            override val cronZone: ZoneId = zone
            override val estimateRuntime: Boolean = false
            override val jobTypeIdentifier: String = "cron-test"
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
    fun `schedule with lastEndedAt computes next cron time`() {
        // Every hour at minute 0
        val spec = createSpec("0 * * * *")
        val lastEndedAt = millis(2025, 6, 15, 10, 30)

        val job = spec.schedule(lastEndedAt)

        assertNotNull(job)
        // Next occurrence after 10:30 should be 11:00
        assertEquals(millis(2025, 6, 15, 11, 0), job.minimumStartAt)
    }

    @Test
    fun `schedule with null lastEndedAt uses current time`() {
        // Every 15 minutes
        val spec = createSpec("*/15 * * * *")
        val before = System.currentTimeMillis()

        val job = spec.schedule(null)

        assertNotNull(job)
        val minStart = job.minimumStartAt!!
        // The computed next cron time should be greater than before (current time)
        assert(minStart > before) {
            "minimumStartAt ($minStart) should be greater than before ($before)"
        }
    }

    @Test
    fun `schedule sets job type from spec`() {
        val spec = createSpec("0 9 * * *")
        val job = spec.schedule(millis(2025, 6, 15, 10, 0))

        assertNotNull(job)
        assertEquals("cron-test", job.type)
    }

    @Test
    fun `schedule sets state to WAITING_FOR_START`() {
        val spec = createSpec("0 9 * * *")
        val job = spec.schedule(millis(2025, 6, 15, 10, 0))

        assertNotNull(job)
        assertEquals(JobState.PENDING, job.state)
    }

    @Test
    fun `schedule respects cron timezone`() {
        val eastern = ZoneId.of("America/New_York")
        // Daily at 9:00 AM in Eastern time
        val spec = createSpec("0 9 * * *", zone = eastern)
        // 12:00 UTC = 8:00 ET (during EDT)
        val lastEndedAt = millis(2025, 6, 15, 12, 0)

        val job = spec.schedule(lastEndedAt)

        assertNotNull(job)
        // Next 9:00 ET should be 13:00 UTC (EDT offset is -4)
        val nextEt = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(job.minimumStartAt!!), eastern
        )
        assertEquals(9, nextEt.hour)
        assertEquals(15, nextEt.dayOfMonth)
    }

    @Test
    fun `schedule with daily cron at midnight`() {
        val spec = createSpec("0 0 * * *")
        val lastEndedAt = millis(2025, 6, 15, 23, 59)

        val job = spec.schedule(lastEndedAt)

        assertNotNull(job)
        assertEquals(millis(2025, 6, 16, 0, 0), job.minimumStartAt)
    }

    @Test
    fun `schedule with weekly cron (every Monday)`() {
        val spec = createSpec("0 0 * * 1")
        // 2025-06-15 is Sunday
        val lastEndedAt = millis(2025, 6, 15, 12, 0)

        val job = spec.schedule(lastEndedAt)

        assertNotNull(job)
        // Next Monday is June 16
        assertEquals(millis(2025, 6, 16, 0, 0), job.minimumStartAt)
    }

    @Test
    fun `cronZone defaults to system default`() {
        // Create a spec that does NOT override cronZone to test the default
        val parsed = CronExpression.parse("0 * * * *")
        val spec = object : CronJobSpec {
            override val cronExpression: CronExpression = parsed
            // Do not override cronZone, use default
            override val estimateRuntime: Boolean = false
            override val jobTypeIdentifier: String = "cron-default-zone"
            override val jobPriority: Int? = null
            override val maxRetriesOnFailure: Int = 0
            override val minSecondsBetweenRetries: Int? = null
            override val retryExceptionBaseTypes: List<KClass<out Exception>> = emptyList()
            override fun getEstimatedRuntimeQueries(jobDetails: JsonObject): List<QueryNode> = emptyList()
            override fun createJob(): Job = object : Job {
                override fun execute(context: JobContext): JobResult = JobCompleted()
            }
        }

        assertEquals(ZoneId.systemDefault(), spec.cronZone)
    }
}
