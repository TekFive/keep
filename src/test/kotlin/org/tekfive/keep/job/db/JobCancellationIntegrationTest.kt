package org.tekfive.keep.job.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import org.tekfive.jfk.toJsonObject
import org.tekfive.keep.db.DbConnection
import org.tekfive.keep.db.db
import org.tekfive.keep.job.*
import org.tekfive.keep.job.dispatch.*
import org.tekfive.keep.job.schedule.ScheduledJobSpec
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Testcontainers
class JobCancellationIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
        @BeforeAll @JvmStatic fun start() {
            PostgresTestSupport.initSchema(postgres)
            AckRegistry.clear()
            AckRegistry.addSource(MapSource(mapOf(
                "JDBC_URL" to postgres.jdbcUrl,
                "JDBC_USER" to postgres.username,
                "JDBC_PASSWORD" to postgres.password,
            )))
            DbConnection.startup()
        }
        @AfterAll @JvmStatic fun stop() { DbConnection.shutdown(); AckRegistry.clear() }
    }

    @BeforeEach fun clean() { PostgresTestSupport.truncateJobsTable(postgres) }
    private val table = JobRecordsTable
    private val spec = object : JobSpec {
        override val jobTypeIdentifier = "cancellation-test"
        override val jobDescription = "Exercises cooperative cancellation."
        override val maxRetriesOnFailure = 1
        override fun createJob(): Job = object : Job {
            override fun execute(context: JobContext): JobResult = JobCompleted()
        }
    }
    private fun capture(id: Long) = assertNotNull(table.tryCaptureRunLock(id, "worker", spec))
    private fun context(record: JobRecord, body: (JobContext) -> JobResult = { JobCompleted() }) =
        DispatchContext(0, object : Job {
            override fun execute(context: JobContext): JobResult = body(context)
        }, spec, record, table, null)
    private fun dispatcher(): Dispatcher {
        val config = TestJobConfiguration()
        return Dispatcher(config, LinkedBlockingQueue(), emptyList(), DatabaseGatekeeper(config), table, "worker")
    }
    private fun finish(context: DispatchContext): ExecuteJobResult {
        val dispatcher = dispatcher()
        return dispatcher.executeJob(context).also { dispatcher.finalizeJob(it, context, spec) }
    }
    private fun load(id: Long) = db { table.getById(id) }

    @Test fun `pending cancellation prevents even a previously selected job from starting`() {
        val id = table.insertJob(spec, minStartAt = System.currentTimeMillis() + 60_000)
        assertTrue(table.requestCancellation(id, "operator-a"))
        val record = load(id)
        assertEquals(JobState.CANCELLED, record.state)
        assertEquals("operator-a", record.cancelRequestedByUserId)
        assertEquals(record.cancelRequestedAt, record.endedAt)
        assertNotNull(record.endedAt)
        assertNull(record.startedAt)
        assertFalse(table.requestCancellation(id, "operator-b"))
        assertFalse(table.requestCancellation(Long.MAX_VALUE, "operator-b"))
        assertNull(table.tryCaptureRunLock(id, "worker", spec))
        assertTrue(table.getJobIdStartCandidates(10, listOf(spec.jobTypeIdentifier), Long.MAX_VALUE).isEmpty())
        val history = JobStateTransitionsTable.getForJob(id)
        assertEquals(listOf(JobState.PENDING, JobState.CANCELLED), history.map { it.toState })
        assertEquals("operator-a", history.last().cancelRequestedByUserId)
        assertEquals(record.cancelRequestedAt, history.last().cancelRequestedAt)
    }

    @Test fun `check-in acknowledges the first cancellation request and finishes without retrying`() {
        val id = table.insertJob(spec)
        val context = context(capture(id)) {
            it.checkIn()
            fail("Work after the cancellation check must not execute")
        }
        context.updateProgress(processedCount = 3, failedCount = 1, totalCount = 10)
        assertTrue(table.requestCancellation(id, "operator-a"))
        val requestedAt = load(id).cancelRequestedAt
        assertFalse(table.requestCancellation(id, "operator-b"))
        assertEquals(JobState.CANCEL_REQUESTED, load(id).state)
        assertNull(load(id).endedAt)
        assertEquals(ExecuteJobResultType.CANCELLED, finish(context).type)
        val record = load(id)
        assertEquals(JobState.CANCELLED, record.state)
        assertNotNull(record.endedAt)
        assertEquals("operator-a", record.cancelRequestedByUserId)
        assertEquals(requestedAt, record.cancelRequestedAt)
        assertEquals(3L, record.processedCount)
        assertNull(record.retryAt)
        assertTrue(table.getJobIdStartCandidates(10, listOf(spec.jobTypeIdentifier), Long.MAX_VALUE).isEmpty())
        val history = JobStateTransitionsTable.getForJob(id)
        assertEquals(listOf(JobState.PENDING, JobState.RUNNING, JobState.CANCEL_REQUESTED, JobState.CANCELLED), history.map { it.toState })
        assertTrue(history.takeLast(2).all { it.cancelRequestedByUserId == "operator-a" && it.cancelRequestedAt == requestedAt })
    }

    @Test fun `normal completion failure and waiting override an unobserved request`() {
        for ((result, expectedState) in listOf(
            JobCompleted("Done", processedCount = 10) to JobState.COMPLETED,
            JobFailed("Failed", processedCount = 10) to JobState.FAILED,
            JobWaiting("Needs input", processedCount = 10) to JobState.WAITING,
        )) {
            val id = table.insertJob(spec)
            val context = context(capture(id)) {
                it.updateProgress(processedCount = 5)
                it.updateDetails(mapOf("cursor" to 5).toJsonObject())
                result
            }
            assertTrue(table.requestCancellation(id, "operator"))
            finish(context)
            val record = load(id)
            assertEquals(expectedState, record.state)
            assertEquals(result.processedCount, record.processedCount)
            assertNotNull(record.endedAt)
            assertEquals("operator", record.cancelRequestedByUserId)
            assertFalse(table.requestCancellation(id, "later-operator"))
            assertEquals(expectedState, JobStateTransitionsTable.getForJob(id).last().toState)
        }
    }

    @Test fun `retry after an unobserved request follows the normal failure policy and clears attribution`() {
        val id = table.insertJob(spec)
        val context = context(capture(id)) { JobFailed("Retry", retryIfAllowed = true) }
        assertTrue(table.requestCancellation(id, "operator"))
        finish(context)
        assertEquals(JobState.FAILED, load(id).state)
        val retryId = table.getJobIdStartCandidates(10, listOf(spec.jobTypeIdentifier), Long.MAX_VALUE).single().first
        val retry = load(retryId)
        assertEquals(id, retry.parentJobId)
        assertNull(retry.cancelRequestedByUserId)
        assertNull(retry.cancelRequestedAt)
    }

    @Test fun `cancellation request retains type and keyed concurrency slots and lock keys`() {
        for (mode in listOf("global", "keyed", "lock")) {
            val limit = if (mode == "lock") null else 1
            val key = if (mode == "keyed") "tenant" else null
            val lock = if (mode == "lock") "resource" else null
            val id = table.insertJob(spec, maxConcurrentJobs = limit, concurrencyKey = key, lockKey = lock)
            val context = context(capture(id)) { it.checkIn(); JobCompleted() }
            assertTrue(table.requestCancellation(id))
            assertNull(load(id).cancelRequestedByUserId)
            assertNotNull(load(id).cancelRequestedAt)
            val waitingId = table.insertJob(spec, maxConcurrentJobs = limit, concurrencyKey = key, lockKey = lock)
            assertEquals(listOf(id), table.getRunningJobs(listOf(spec.jobTypeIdentifier)).map { it.id })
            assertTrue(table.getJobIdStartCandidates(10, listOf(spec.jobTypeIdentifier), Long.MAX_VALUE).isEmpty())
            assertNull(table.tryCaptureRunLock(waitingId, "other-worker", spec))
            if (mode == "lock") {
                assertFailsWith<SQLException> {
                    PostgresTestSupport.getConnection(postgres).use { connection ->
                        connection.createStatement().use { it.execute("UPDATE job_records SET state = 2 WHERE id = $waitingId") }
                    }
                }
            }
            finish(context)
            assertNotNull(table.tryCaptureRunLock(waitingId, "other-worker", spec))
            table.tryMarkEnded(waitingId, System.currentTimeMillis(), JobState.COMPLETED)
        }
    }

    @Test fun `scheduled cancellation retains its scheduled chain until it ends`() {
        val scheduled = object : ScheduledJobSpec, JobSpec by spec {
            override fun schedule(lastEndedAt: Long?): JobRecord = createScheduledJob(null)
        }
        val id = table.insertJob(scheduled)
        val context = context(capture(id)) { it.checkIn(); JobCompleted() }
        table.requestCancellation(id, "operator")
        assertTrue(table.getScheduleStates(listOf(spec.jobTypeIdentifier)).getValue(spec.jobTypeIdentifier).hasNonTerminatedJob)
        assertFailsWith<SQLException> { db { table.create(scheduled.schedule(null)) } }
        finish(context)
        assertFalse(table.getScheduleStates(listOf(spec.jobTypeIdentifier)).getValue(spec.jobTypeIdentifier).hasNonTerminatedJob)
        assertNotNull(db { table.create(scheduled.schedule(null)) })
    }

    @Test fun `interruption completes a pending cancellation instead of requeueing`() {
        val id = table.insertJob(spec)
        val context = context(capture(id)) { throw InterruptedException() }
        assertTrue(table.requestCancellation(id, "operator"))
        finish(context)
        assertEquals(JobState.CANCELLED, load(id).state)
        assertNotNull(load(id).endedAt)
        assertEquals("operator", load(id).cancelRequestedByUserId)
    }

    @Test fun `restart cancels orphaned requests and requeues other orphaned executions`() {
        val cancelledId = table.insertJob(spec)
        capture(cancelledId)
        table.requestCancellation(cancelledId, "operator")
        val runningId = table.insertJob(spec)
        capture(runningId)
        assertEquals(listOf(runningId), table.requeueOrphanedRunningJobs("worker", Long.MAX_VALUE).map { it.id })
        assertEquals(JobState.CANCELLED, load(cancelledId).state)
        assertNotNull(load(cancelledId).endedAt)
        assertEquals("operator", load(cancelledId).cancelRequestedByUserId)
        assertEquals(JobState.PENDING, load(runningId).state)
    }

    @Test fun `timeout still terminates an execution that does not acknowledge cancellation`() {
        val id = table.insertJob(spec)
        val record = capture(id)
        assertTrue(table.requestCancellation(id, "operator"))
        assertNotNull(table.tryMarkTimedOut(id, Long.MAX_VALUE, System.currentTimeMillis(), expectedExecutionToken = record.executionToken))
        assertEquals(JobState.TIMED_OUT, load(id).state)
        assertEquals("operator", load(id).cancelRequestedByUserId)
    }

    @Test fun `cancellation rolls back job transaction before finalization`() {
        val id = table.insertJob(spec)
        val context = context(capture(id)) {
            db {
                it.updateProgress(processedCount = 10)
                it.updateDetails(mapOf("uncommitted" to true).toJsonObject())
                it.checkIn()
                JobCompleted()
            }
        }
        assertTrue(table.requestCancellation(id, "operator"))
        finish(context)
        assertEquals(JobState.CANCELLED, load(id).state)
        assertNull(load(id).processedCount)
        assertNull(load(id).jobDetails)
    }

    @Test fun `completion racing cancellation always retains the normal result`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(10) {
                val id = table.insertJob(spec)
                val context = context(capture(id))
                val barrier = CyclicBarrier(2)
                val request = executor.submit(Callable {
                    barrier.await(5, TimeUnit.SECONDS)
                    table.requestCancellation(id, "operator")
                })
                val complete = executor.submit(Callable {
                    barrier.await(5, TimeUnit.SECONDS)
                    dispatcher().finalizeJob(ExecuteJobResult(ExecuteJobResultType.SUCCESS), context, spec)
                })
                val requested = request.get(5, TimeUnit.SECONDS)
                complete.get(5, TimeUnit.SECONDS)
                val record = load(id)
                assertEquals(JobState.COMPLETED, record.state)
                assertEquals(if (requested) "operator" else null, record.cancelRequestedByUserId)
                assertNotNull(record.endedAt)
            }
        } finally { executor.shutdownNow() }
    }
}
