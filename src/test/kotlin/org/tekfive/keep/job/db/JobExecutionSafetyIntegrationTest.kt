package org.tekfive.keep.job.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import org.tekfive.jfk.toJsonObject
import org.tekfive.keep.db.DbConnection
import org.tekfive.keep.db.db
import org.tekfive.keep.db.rollback
import org.tekfive.keep.job.*
import org.tekfive.keep.job.dispatch.*
import org.tekfive.keep.job.schedule.ScheduledJobSpec
import org.tekfive.keep.json.query.JsonPathOperator
import org.tekfive.keep.json.query.QueryNode
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*

@Testcontainers
class JobExecutionSafetyIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
        @BeforeAll @JvmStatic fun start() {
            PostgresTestSupport.initSchema(postgres)
            AckRegistry.clear()
            val separator = if (postgres.jdbcUrl.contains('?')) "&" else "?"
            AckRegistry.addSource(MapSource(mapOf(
                "JDBC_URL" to postgres.jdbcUrl + separator + "options=-c%20lock_timeout%3D1500",
                "JDBC_USER" to postgres.username,
                "JDBC_PASSWORD" to postgres.password,
            )))
            DbConnection.startup()
        }
        @AfterAll @JvmStatic fun stop() { DbConnection.shutdown(); AckRegistry.clear() }
    }

    @BeforeEach fun clean() { PostgresTestSupport.truncateJobsTable(postgres) }
    @AfterEach fun removeFailureInjection() { sql("DROP TRIGGER IF EXISTS reject_retry ON job_records") }
    private val table = JobRecordsTable
    private val spec = object : JobSpec {
        override val jobTypeIdentifier = "safety-test"
        override val jobDescription = "Exercises job lifecycle guarantees."
        override val maxRetriesOnFailure = 1
        override val minSecondsBetweenRetries = 60
        override fun createJob(): Job = object : Job {
            override fun execute(context: JobContext): JobResult = JobCompleted()
        }
    }
    private fun capture(id: Long, jobSpec: JobSpec = spec) =
        assertNotNull(db { table.tryCaptureRunLock(id, "worker", jobSpec) })
    private fun context(record: JobRecord, jobSpec: JobSpec = spec) =
        DispatchContext(1, jobSpec.createJob(), jobSpec, record, table, null)
    private fun dispatcher(): Dispatcher {
        val config = TestJobConfiguration()
        return Dispatcher(config, LinkedBlockingQueue(), emptyList(), DatabaseGatekeeper(config), table, "worker")
    }
    private fun load(id: Long) = db { table.getById(id) }
    private fun sql(sql: String) = PostgresTestSupport.getConnection(postgres).use { connection ->
        connection.createStatement().use { it.execute(sql) }
    }

    @Test fun `copies of terminal jobs are runnable and link to their original`() {
        for (state in listOf(JobState.FAILED, JobState.COMPLETED)) {
            val id = table.insertJob(spec)
            capture(id)
            table.tryMarkEnded(id, System.currentTimeMillis(), state, processedCount = 10)
            val copy = table.launchCopy(load(id))
            assertEquals(JobState.PENDING, copy.state)
            assertEquals(id, copy.parentJobId)
            assertEquals(2, copy.attempt)
            assertEquals(load(id).runId, copy.runId)
            assertNull(copy.startedAt)
            assertNull(copy.endedAt)
            assertNull(copy.processedCount)
            assertNull(copy.executionToken)
            assertTrue(table.getJobIdStartCandidates(10, listOf(spec.jobTypeIdentifier), System.currentTimeMillis()).any { it.first == copy.id })
        }
    }

    @Test fun `retry uses committed checkpoints and ignores rolled back changes`() {
        val id = table.insertJob(spec, details = mapOf("cursor" to 1).toJsonObject())
        val context = context(capture(id))
        context.updateDetails(mapOf("cursor" to 2).toJsonObject())
        assertEquals(2, context.details!!["cursor"].int)
        db {
            context.updateDetails(mapOf("cursor" to 3).toJsonObject())
            assertEquals(3, context.details!!["cursor"].int)
            rollback()
        }
        assertEquals(2, context.details!!["cursor"].int)
        dispatcher().finalizeJob(ExecuteJobResult(ExecuteJobResultType.FAILED_BUT_RETRY_IF_ALLOWED), context, spec)
        val retryId = table.getJobIdStartCandidates(10, listOf(spec.jobTypeIdentifier), Long.MAX_VALUE).single().first
        assertEquals(2, load(retryId).jobDetails!!["cursor"].int)
        assertNull(load(id).retryAt)
    }

    @Test fun `transactional progress and details do not block independently committed heartbeats`() {
        val id = table.insertJob(spec)
        val context = context(capture(id))
        val heartbeatAt = context.startedAt + 2_000
        val executor = Executors.newSingleThreadExecutor()
        try {
            db {
                context.updateDetails(mapOf("cursor" to 2).toJsonObject())
                context.updateProgress(processedCount = 1)
                context.checkIn(heartbeatAt)
                // The monitor must skip a locked business row rather than block the coordinator.
                assertNull(executor.submit(Callable {
                    table.tryMarkTimedOut(id, heartbeatAt + 1, heartbeatAt + 2)
                }).get(1, TimeUnit.SECONDS))
                PostgresTestSupport.getConnection(postgres).use { connection ->
                    connection.prepareStatement("SELECT last_checkin_at FROM job_heartbeats WHERE job_record_id = ?").use { statement ->
                        statement.setLong(1, id)
                        statement.executeQuery().use { rs -> assertTrue(rs.next()); assertEquals(heartbeatAt, rs.getLong(1)) }
                    }
                }
                rollback()
            }
            assertNull(load(id).processedCount)
            assertNull(load(id).jobDetails)
            assertEquals(heartbeatAt, table.getRunningJobs(listOf(spec.jobTypeIdentifier)).single().lastCheckInAt)
            assertNull(table.tryMarkTimedOut(id, heartbeatAt - 1, heartbeatAt + 3))
        } finally { executor.shutdownNow() }
    }

    @Test fun `mixed scope captures serialize before checking the type-wide limit`() {
        val keyed = table.insertJob(spec, maxConcurrentJobs = 1, concurrencyKey = "tenant-a")
        val global = table.insertJob(spec, maxConcurrentJobs = 1)
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit(Callable {
                db {
                    capture(keyed)
                    captured.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
            })
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            val second = executor.submit(Callable { table.tryCaptureRunLock(global, "other-worker", spec) })
            assertFailsWith<TimeoutException> { second.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertNull(second.get(5, TimeUnit.SECONDS))
            assertEquals(1, table.getRunningJobs(listOf(spec.jobTypeIdentifier)).size)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `keyed and unlimited jobs respect an existing type-wide limit`() {
        capture(table.insertJob(spec, maxConcurrentJobs = 1))
        for (id in listOf(table.insertJob(spec, maxConcurrentJobs = 1, concurrencyKey = "tenant-a"), table.insertJob(spec))) {
            assertNull(table.tryCaptureRunLock(id, "other-worker", spec))
        }
        assertTrue(table.getJobIdStartCandidates(10, listOf(spec.jobTypeIdentifier), Long.MAX_VALUE).isEmpty())
    }

    @Test fun `old execution cannot change progress details heartbeat outcome or retry of its replacement`() {
        val id = table.insertJob(spec)
        val old = context(capture(id))
        assertTrue(table.tryRequeue(id, old.jobRecord.executionToken))
        val replacement = capture(id)
        assertNotEquals(old.jobRecord.executionToken, replacement.executionToken)
        assertFailsWith<JobOwnershipLostException> { old.updateProgress(processedCount = 99) }
        assertFailsWith<JobOwnershipLostException> { old.updateDetails(mapOf("stale" to true).toJsonObject()) }
        assertFailsWith<JobOwnershipLostException> { old.checkIn(old.startedAt + 2_000) }
        for (result in listOf(ExecuteJobResultType.SUCCESS, ExecuteJobResultType.INTERRUPTED, ExecuteJobResultType.FAILED_BUT_RETRY_IF_ALLOWED, ExecuteJobResultType.CANCELLED)) {
            dispatcher().finalizeJob(ExecuteJobResult(result), old, spec)
            assertEquals(JobState.RUNNING, load(id).state)
        }
        assertNull(load(id).processedCount)
        assertNull(load(id).jobDetails)
        assertNull(load(id).retryAt)
        assertNull(load(id).lastCheckInAt)
        // A timeout sweep based on an older execution must not terminate the replacement either.
        assertNull(table.tryMarkTimedOut(id, Long.MAX_VALUE, System.currentTimeMillis(), expectedExecutionToken = old.jobRecord.executionToken))
    }

    @Test fun `numeric equality works for runtime estimation`() {
        val id = table.insertJob(spec, details = mapOf("count" to 3).toJsonObject())
        val record = capture(id)
        table.tryMarkEnded(id, record.startedAt!! + 5_000, JobState.COMPLETED)
        for (value in listOf<Number>(3, 3L, 3.0)) {
            assertEquals(5, table.getAverageRuntimeSecs(spec.jobTypeIdentifier, 10,
                QueryNode.Condition(JsonPathOperator.Equals(listOf("count"), value))))
        }
        assertNull(table.getAverageRuntimeSecs(spec.jobTypeIdentifier, 10,
            QueryNode.Condition(JsonPathOperator.Equals(listOf("count"), 4))))
    }

    @Test fun `failed retry insert survives until a new coordinator recovers it`() {
        val scheduled = object : ScheduledJobSpec, JobSpec by spec {
            override fun schedule(lastEndedAt: Long?): JobRecord = createScheduledJob(System.currentTimeMillis() + 60_000)
        }
        val id = table.insertJob(scheduled)
        val context = context(capture(id, scheduled), scheduled)
        sql("""
            CREATE OR REPLACE FUNCTION reject_retry_insert() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                IF NEW.parent_job_id IS NOT NULL THEN RAISE EXCEPTION 'Injected retry insert failure'; END IF;
                RETURN NEW;
            END;
            ${'$'}${'$'};
            CREATE TRIGGER reject_retry BEFORE INSERT ON job_records FOR EACH ROW EXECUTE FUNCTION reject_retry_insert();
        """.trimIndent())
        dispatcher().finalizeJob(ExecuteJobResult(ExecuteJobResultType.FAILED_BUT_RETRY_IF_ALLOWED), context, scheduled)
        assertEquals(JobState.FAILED, load(id).state)
        assertNotNull(load(id).retryAt)
        assertEquals(listOf(id), table.getRetryCandidates(listOf(spec.jobTypeIdentifier)))
        assertTrue(table.getScheduleStates(listOf(spec.jobTypeIdentifier)).getValue(spec.jobTypeIdentifier).hasNonTerminatedJob)
        assertFailsWith<SQLException> { table.create(scheduled.schedule(null)) }
        sql("DROP TRIGGER reject_retry ON job_records")

        val registry = JobRegistry().apply { register(scheduled) }
        val coordinator = JobCoordinator("recovery-worker", registry, object : DefaultJobConfiguration() {
            override val dispatchCount = 0
            override val pollSeconds = 1
        })
        try {
            coordinator.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (load(id).retryAt != null && System.nanoTime() < deadline) Thread.sleep(20)
            assertNull(load(id).retryAt)
            val retry = load(table.getJobIdStartCandidates(10, listOf(spec.jobTypeIdentifier), Long.MAX_VALUE).single().first)
            assertEquals(id, retry.parentJobId)
            assertEquals(context.runId, retry.runId)
            assertEquals(2, retry.attempt)
            assertTrue(retry.minimumStartAt!! >= context.startedAt + 60_000)
            assertNull(table.enqueueRetry(id), "Recovery must not create another retry")
        } finally { coordinator.stop(waitForStop = true) }
    }

    @Test fun `concurrent recovery creates exactly one retry`() {
        val id = table.insertJob(spec)
        val record = capture(id)
        table.tryMarkEnded(id, System.currentTimeMillis(), JobState.FAILED,
            expectedExecutionToken = record.executionToken, retryAt = System.currentTimeMillis())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { executor.submit(Callable { table.enqueueRetry(id) }) }.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it != null })
            assertNull(load(id).retryAt)
            assertEquals(1, table.getJobIdStartCandidates(10, listOf(spec.jobTypeIdentifier), Long.MAX_VALUE).size)
        } finally { executor.shutdownNow() }
    }
    @Test fun `cleanup preserves old failed jobs with outstanding retry requests`() {
        val id = table.insertJob(spec)
        val record = capture(id)
        table.tryMarkEnded(id, 0, JobState.FAILED, expectedExecutionToken = record.executionToken, retryAt = 0)
        JobRecordCleaner().execute(context(record))
        assertEquals(0L, load(id).retryAt)
        assertNotNull(table.enqueueRetry(id))
    }

}
