package org.tekfive.keep.job.dispatch

import org.junit.jupiter.api.Test
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.job.*
import org.tekfive.keep.job.db.*
import java.sql.SQLException
import java.time.Instant
import java.time.InstantSource
import java.util.concurrent.LinkedBlockingQueue
import kotlin.reflect.KClass
import kotlin.test.assertEquals

class DispatcherTest {

    private val fixedInstant = InstantSource.fixed(Instant.ofEpochMilli(1_000_000L))

    val successJob = object : Job {
        override fun execute(context: JobContext): JobResult {
            return JobCompleted()
        }
    }


    private fun createDispatcher(config: JobConfiguration = TestJobConfiguration()): Dispatcher {
        val gatekeeper = DatabaseGatekeeper(config)
        val jobsTable = JobRecordsTable
        val dispatchers = mutableListOf<Dispatcher>()
        val queue = LinkedBlockingQueue<Pair<Long, JobSpec>>()
        return Dispatcher(config, queue, dispatchers, gatekeeper, jobsTable, "test-system")
    }

    private fun createDispatchContext(
        jobSpec: JobSpec,
        attempt: Int = 1,
        config: JobConfiguration = TestJobConfiguration()
    ): DispatchContext {

        val jobRecord = JobRecord(
            type = jobSpec.jobTypeIdentifier,
            createdAt = 1_000_000L,
            priority = 0,
            parentJobId = null,
            minimumStartAt = null,
            attempt = attempt,
            estimatedRuntimeSeconds = null,
            state = JobState.RUNNING,
            jobDetails = null,
            systemIdentifier = "test-system",
            startedAt = 1_000_000L,
            lastCheckInAt = null,
            endedAt = null,
            failureDetails = null,
        )
        jobRecord.linkToDB(1L)
        val jobsTable = JobRecordsTable
        return DispatchContext(30, jobSpec.createJob(), jobSpec, jobRecord, jobsTable, null, System.currentTimeMillis())
    }

    private fun jobSpecWith(
        executor: Job,
        maxRetries: Int = 0,
        retryExceptionBaseTypes: List<KClass<out Exception>> = emptyList()
    ): JobSpec {
        return object : JobSpec {
            override val estimateRuntime: Boolean = false
            override val jobTypeIdentifier: String = "test-type"
            override val jobPriority: Int? = null
            override val maxRetriesOnFailure: Int = maxRetries
            override val minSecondsBetweenRetries: Int? = null
            override val retryExceptionBaseTypes: List<KClass<out Exception>> = retryExceptionBaseTypes
            override fun getEstimatedRuntimeQueries(jobDetails: JsonObject): List<QueryNode> = emptyList()
            override fun createJob(): Job = executor
        }
    }

    // --- executeJob tests ---

    @Test
    fun `executeJob returns SUCCESS when job returns JobSuccess`() {
        val spec = jobSpecWith(object : Job {
            override fun execute(context: JobContext): JobResult = JobCompleted()
        })
        val dispatcher = createDispatcher()
        val context = createDispatchContext(spec)

        val result = dispatcher.executeJob(context)

        assertEquals(ExecuteJobResultType.SUCCESS, result.type)
    }

    @Test
    fun `executeJob returns FAILED when job returns JobFailure without retry`() {
        val spec = jobSpecWith(object : Job {
            override fun execute(context: JobContext): JobResult =
                JobFailed("something broke", retryIfAllowed = false)
        })
        val dispatcher = createDispatcher()
        val context = createDispatchContext(spec)

        val result = dispatcher.executeJob(context)

        assertEquals(ExecuteJobResultType.FAILED, result.type)
    }

    @Test
    fun `executeJob returns RETRY_IF_ALLOWED when job returns JobFailure with retry`() {
        val spec = jobSpecWith(object : Job {
            override fun execute(context: JobContext): JobResult =
                JobFailed("transient error", retryIfAllowed = true)
        })
        val dispatcher = createDispatcher()
        val context = createDispatchContext(spec)

        val result = dispatcher.executeJob(context)

        assertEquals(ExecuteJobResultType.FAILED_BUT_RETRY_IF_ALLOWED, result.type)
    }

    @Test
    fun `executeJob returns ALREADY_TERMINATED when job throws JobEndedException`() {
        val spec = jobSpecWith(object : Job {
            override fun execute(context: JobContext): JobResult {
                throw JobNotFoundException(1L)
            }
        })
        val dispatcher = createDispatcher()
        val context = createDispatchContext(spec)

        val result = dispatcher.executeJob(context)

        assertEquals(ExecuteJobResultType.ALREADY_TERMINATED, result.type)
    }

    @Test
    fun `executeJob returns RETRY_IF_ALLOWED when exception matches retryExceptionBaseTypes`() {
        val spec = jobSpecWith(
            executor = object : Job {
                override fun execute(context: JobContext): JobResult {
                    throw IllegalStateException("retriable")
                }
            },
            retryExceptionBaseTypes = listOf(IllegalStateException::class)
        )
        val dispatcher = createDispatcher()
        val context = createDispatchContext(spec)

        val result = dispatcher.executeJob(context)

        assertEquals(ExecuteJobResultType.FAILED_BUT_RETRY_IF_ALLOWED, result.type)
    }

    @Test
    fun `executeJob returns FAILED when exception does not match retryExceptionBaseTypes`() {
        val spec = jobSpecWith(
            executor = object : Job {
                override fun execute(context: JobContext): JobResult {
                    throw IllegalArgumentException("not retriable")
                }
            },
            retryExceptionBaseTypes = listOf(IllegalStateException::class)
        )
        val dispatcher = createDispatcher()
        val context = createDispatchContext(spec)

        val result = dispatcher.executeJob(context)

        assertEquals(ExecuteJobResultType.FAILED, result.type)
    }

    @Test
    fun `executeJob returns FAILED when exception thrown and no retryExceptionBaseTypes`() {
        val spec = jobSpecWith(object : Job {
            override fun execute(context: JobContext): JobResult {
                throw RuntimeException("unexpected")
            }
        })
        val dispatcher = createDispatcher()
        val context = createDispatchContext(spec)

        val result = dispatcher.executeJob(context)

        assertEquals(ExecuteJobResultType.FAILED, result.type)
    }

    @Test
    fun `executeJob returns REDO_IF_ALLOWED for recoverable OnCheckInSqlException`() {
        val spec = jobSpecWith(object : Job {
            override fun execute(context: JobContext): JobResult {
                throw OnCheckInSqlException(SQLException("connection lost", "08001"))
            }
        })
        val dispatcher = createDispatcher()
        val context = createDispatchContext(spec)

        val result = dispatcher.executeJob(context)

        assertEquals(ExecuteJobResultType.REDO_IMMEDIATELY_IF_ALLOWED, result.type)
    }

    @Test
    fun `executeJob returns FAILED for non-recoverable OnCheckInSqlException`() {
        val spec = jobSpecWith(object : Job {
            override fun execute(context: JobContext): JobResult {
                throw OnCheckInSqlException(SQLException("syntax error", "42000"))
            }
        })
        val dispatcher = createDispatcher()
        val context = createDispatchContext(spec)

        val result = dispatcher.executeJob(context)

        assertEquals(ExecuteJobResultType.FAILED, result.type)
    }
}
