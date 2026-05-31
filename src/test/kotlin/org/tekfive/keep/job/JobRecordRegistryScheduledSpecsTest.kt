package org.tekfive.keep.job

import org.junit.jupiter.api.Test
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.job.db.JobRecord
import org.tekfive.keep.job.db.QueryNode
import org.tekfive.keep.job.schedule.ScheduledJobSpec
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JobRecordRegistryScheduledSpecsTest {

    private fun createScheduledSpec(typeId: String): ScheduledJobSpec {
        return object : ScheduledJobSpec {
            override val estimateRuntime: Boolean = false
            override val jobTypeIdentifier: String = typeId
            override val jobPriority: Int? = null
            override val maxRetriesOnFailure: Int = 0
            override val minSecondsBetweenRetries: Int? = null
            override val retryExceptionBaseTypes: List<KClass<out Exception>> = emptyList()
            override fun getEstimatedRuntimeQueries(jobDetails: JsonObject): List<QueryNode> = emptyList()
            override fun createJob(): Job = object : Job {
                override fun execute(context: JobContext): JobResult = JobCompleted()
            }

            override fun schedule(lastEndedAt: Long?): JobRecord? {
                return createScheduledJob(lastEndedAt ?: System.currentTimeMillis())
            }
        }
    }

    private fun createPlainSpec(typeId: String): JobSpec {
        return TestJobSpec(typeId)
    }

    @Test
    fun `scheduledJobSpecs returns empty list when no specs registered`() {
        val registry = JobRegistry()
        assertTrue(registry.scheduledJobSpecs.isEmpty())
    }

    @Test
    fun `scheduledJobSpecs returns empty list when only plain specs registered`() {
        val registry = JobRegistry()
        registry.register(createPlainSpec("plain-1"))
        registry.register(createPlainSpec("plain-2"))
        assertTrue(registry.scheduledJobSpecs.isEmpty())
    }

    @Test
    fun `scheduledJobSpecs returns only scheduled specs`() {
        val registry = JobRegistry()
        registry.register(createPlainSpec("plain"))
        val scheduled = createScheduledSpec("scheduled")
        registry.register(scheduled)

        val result = registry.scheduledJobSpecs
        assertEquals(1, result.size)
        assertEquals("scheduled", result[0].jobTypeIdentifier)
    }

    @Test
    fun `scheduledJobSpecs returns all scheduled specs when multiple registered`() {
        val registry = JobRegistry()
        registry.register(createScheduledSpec("sched-1"))
        registry.register(createScheduledSpec("sched-2"))
        registry.register(createPlainSpec("plain"))

        val result = registry.scheduledJobSpecs
        assertEquals(2, result.size)
        val ids = result.map { it.jobTypeIdentifier }.toSet()
        assertEquals(setOf("sched-1", "sched-2"), ids)
    }
}
