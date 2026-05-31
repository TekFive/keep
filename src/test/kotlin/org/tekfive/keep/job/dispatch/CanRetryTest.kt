package org.tekfive.keep.job.dispatch

import org.junit.jupiter.api.Test
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.job.*
import org.tekfive.keep.job.db.DatabaseGatekeeper
import org.tekfive.keep.job.db.JobRecordsTable
import org.tekfive.keep.job.db.QueryNode
import org.tekfive.keep.job.db.TestJobConfiguration
import java.util.concurrent.LinkedBlockingQueue
import kotlin.reflect.KClass
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanRetryTest {

    private fun createDispatcher(): Dispatcher {
        val config = TestJobConfiguration()
        val gatekeeper = DatabaseGatekeeper(config)
        val jobsTable = JobRecordsTable
        val dispatchers = mutableListOf<Dispatcher>()
        val queue = LinkedBlockingQueue<Pair<Long, JobSpec>>()
        return Dispatcher(config, queue, dispatchers, gatekeeper, jobsTable, "test-system")
    }

    private fun createJobContext(attempt: Int): JobContext {
        val job = object : Job {
            override fun execute(context: JobContext): JobResult {
                return JobCompleted()
            }
        }

        return object : JobContext {
            override val jobId: Long = 1L
            override val startedAt: Long = 0L
            override val type: String = "test"
            override val createdAt: Long = 0L
            override val attempt: Int = attempt
            override val maxRetries: Int = 3
            override val estimatedRuntimeSeconds: Int? = null
            override val details: JsonObject? = null
            override val log: JobLogger = JobLogger( job, this, null)
            override fun checkIn(now: Long) {}
            override fun updateDetails(details: JsonObject) {}
        }
    }

    private fun createJobSpec(
        maxRetries: Int = 3,
        retryExceptionBaseTypes: List<KClass<out Exception>> = emptyList()
    ): JobSpec {
        return object : JobSpec {
            override val estimateRuntime: Boolean = false
            override val jobTypeIdentifier: String = "test"
            override val jobPriority: Int? = null
            override val maxRetriesOnFailure: Int = maxRetries
            override val minSecondsBetweenRetries: Int? = null
            override val retryExceptionBaseTypes: List<KClass<out Exception>> = retryExceptionBaseTypes
            override fun getEstimatedRuntimeQueries(jobDetails: JsonObject): List<QueryNode> = emptyList()
            override fun createJob(): Job = object : Job {
                override fun execute(context: JobContext): JobResult = JobCompleted()
            }
        }
    }

    @Test
    fun `returns false when maxRetries is 0`() {
        val dispatcher = createDispatcher()
        val context = createJobContext(attempt = 1)
        val spec = createJobSpec(maxRetries = 0)
        assertFalse(dispatcher.canRetry(context, spec))
    }

    @Test
    fun `returns true when attempts remain and no cause`() {
        val dispatcher = createDispatcher()
        val context = createJobContext(attempt = 1)
        val spec = createJobSpec(maxRetries = 3)
        assertTrue(dispatcher.canRetry(context, spec))
    }

    @Test
    fun `returns false when attempts exhausted`() {
        val dispatcher = createDispatcher()
        val context = createJobContext(attempt = 4)
        val spec = createJobSpec(maxRetries = 3)
        assertFalse(dispatcher.canRetry(context, spec))
    }

    @Test
    fun `returns true when cause matches retry exception types`() {
        val dispatcher = createDispatcher()
        val context = createJobContext(attempt = 1)
        val spec = createJobSpec(
            maxRetries = 3,
            retryExceptionBaseTypes = listOf(IllegalStateException::class)
        )
        assertTrue(dispatcher.canRetry(context, spec, IllegalStateException("retry me")))
    }

    @Test
    fun `returns false when cause does not match retry exception types`() {
        val dispatcher = createDispatcher()
        val context = createJobContext(attempt = 1)
        val spec = createJobSpec(
            maxRetries = 3,
            retryExceptionBaseTypes = listOf(IllegalStateException::class)
        )
        assertFalse(dispatcher.canRetry(context, spec, IllegalArgumentException("no retry")))
    }

    @Test
    fun `returns true when cause is subclass of retry exception type`() {
        val dispatcher = createDispatcher()
        val context = createJobContext(attempt = 1)
        val spec = createJobSpec(
            maxRetries = 3,
            retryExceptionBaseTypes = listOf(RuntimeException::class)
        )
        assertTrue(dispatcher.canRetry(context, spec, IllegalStateException("subclass")))
    }
}
