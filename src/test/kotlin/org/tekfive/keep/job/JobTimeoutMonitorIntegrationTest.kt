package org.tekfive.keep.job

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import org.tekfive.keep.db.DbConnection
import org.tekfive.keep.db.db
import org.tekfive.keep.job.db.DatabaseGatekeeper
import org.tekfive.keep.job.db.JobRecord
import org.tekfive.keep.job.db.JobRecordsTable
import org.tekfive.keep.job.db.PostgresTestSupport
import org.tekfive.keep.job.dispatch.DispatchContext
import org.tekfive.jfk.json
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class JobTimeoutMonitorIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @BeforeAll
        @JvmStatic
        fun initSchema() {
            PostgresTestSupport.initSchema(postgres)
            AckRegistry.clear()
            AckRegistry.addSource(
                MapSource(
                    mapOf(
                        "JDBC_URL" to postgres.jdbcUrl,
                        "JDBC_USER" to postgres.username,
                        "JDBC_PASSWORD" to postgres.password,
                    )
                )
            )
            DbConnection.startup()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            DbConnection.shutdown()
            AckRegistry.clear()
        }
    }

    @BeforeEach
    fun cleanUp() {
        PostgresTestSupport.truncateJobsTable(postgres)
    }

    @Test
    fun `sweep marks stale running job timed out and invokes callback`() {
        val now = 20_000L
        val spec = TimeoutSpec("default-timeout")
        val jobId = runningJob(spec, startedAt = now - 11_000L, lastCheckInAt = null)
        val monitor = monitor(defaultTimeoutSeconds = 10, spec)

        monitor.sweep(now)

        val job = load(jobId)
        assertEquals(JobState.TIMED_OUT, job.state)
        assertEquals(now, job.endedAt)
        assertTrue(job.failureDetails!!.contains("10 seconds"))
        assertEquals(jobId, spec.timedOutJob?.id)
        assertEquals(now, spec.timedOutAt)
        assertEquals(10, spec.timedOutAfterSeconds)
    }

    @Test
    fun `sweep uses job spec timeout override`() {
        val now = 40_000L
        val spec = TimeoutSpec("override-timeout", timeoutSeconds = 30)
        val jobId = runningJob(spec, startedAt = now - 20_000L, lastCheckInAt = null)
        val monitor = monitor(defaultTimeoutSeconds = 10, spec)

        monitor.sweep(now)

        assertEquals(JobState.RUNNING, load(jobId).state)
        assertNull(spec.timedOutJob)
    }

    @Test
    fun `sweep uses last check-in instead of started time`() {
        val now = 100_000L
        val spec = TimeoutSpec("checkin-timeout")
        val jobId = runningJob(spec, startedAt = now - 60_000L, lastCheckInAt = now - 2_000L)
        val monitor = monitor(defaultTimeoutSeconds = 10, spec)

        monitor.sweep(now)

        assertEquals(JobState.RUNNING, load(jobId).state)
        assertNull(spec.timedOutJob)
    }

    @Test
    fun `jobs of one type use independent heartbeat limits`() {
        val spec = TimeoutSpec("per-job", timeoutSeconds = 60)
        val expired = runningJob(spec, 0L, null, timeoutSeconds = 10)
        val active = runningJob(spec, 0L, null, timeoutSeconds = 30)
        val disabled = runningJob(spec, 0L, null, timeoutSeconds = 0)

        monitor(5, spec).sweep(20_000L)

        assertEquals(JobState.TIMED_OUT, load(expired).state)
        assertEquals(JobState.RUNNING, load(active).state)
        assertEquals(JobState.RUNNING, load(disabled).state)
        assertEquals(JobTimeoutReason.HEARTBEAT, spec.reason)
    }

    @Test
    fun `runtime limit expires at the boundary despite a fresh heartbeat`() {
        val spec = TimeoutSpec("runtime")
        val expired = runningJob(spec, 0L, 10_000L, timeoutSeconds = 0, maxRuntimeSeconds = 10)
        val active = runningJob(spec, 1L, 10_000L, maxRuntimeSeconds = 10)

        monitor(5, spec).sweep(10_000L)

        assertEquals(JobState.TIMED_OUT, load(expired).state)
        assertEquals(JobState.RUNNING, load(active).state)
        assertEquals(JobTimeoutReason.MAX_RUNTIME, spec.reason)
        assertEquals("Job exceeded its maximum runtime of 10 seconds.", load(expired).failureDetails)
    }

    @Test
    fun `runtime limits fall back to spec then configuration and allow zero override`() {
        val spec = TimeoutSpec("spec-runtime", timeoutSeconds = 0, maxRuntimeSeconds = 10)
        val defaultSpec = TimeoutSpec("default-runtime", timeoutSeconds = 0)
        val specJob = runningJob(spec, 0L, 20_000L)
        val defaultJob = runningJob(defaultSpec, 0L, 20_000L)
        val disabled = runningJob(spec, 0L, 20_000L, maxRuntimeSeconds = 0)

        val monitor = monitor(5, spec, defaultSpec, maxRuntimeSeconds = 30)
        monitor.sweep(20_000L)
        assertEquals(JobState.TIMED_OUT, load(specJob).state)
        assertEquals(JobState.RUNNING, load(defaultJob).state)
        monitor.sweep(30_000L)
        assertEquals(JobState.TIMED_OUT, load(defaultJob).state)
        assertEquals(JobState.RUNNING, load(disabled).state)
    }

    @Test
    fun `queued jobs do not consume runtime and completed jobs cannot time out`() {
        val spec = TimeoutSpec("queued-runtime", timeoutSeconds = 0, maxRuntimeSeconds = 1)
        val queued = JobRecordsTable.insertJob(spec)
        val completed = runningJob(spec, 0L, null)
        db { JobRecordsTable.tryMarkEnded(completed, 500L, JobState.COMPLETED) }

        monitor(5, spec).sweep(Long.MAX_VALUE)

        assertEquals(JobState.PENDING, load(queued).state)
        assertEquals(JobState.COMPLETED, load(completed).state)
        assertNull(spec.reason)
    }

    @Test
    fun `runtime callback failure rolls back and retries with the same reason`() {
        var attempts = 0
        val spec = object : JobSpec {
            override val jobTypeIdentifier = "retry-runtime"
            override fun createJob(): Job = error("Not dispatched")
            override fun onJobTimedOut(jobRecord: JobRecord, timedOutAt: Long, timeoutSeconds: Int, reason: JobTimeoutReason) {
                assertEquals(JobTimeoutReason.MAX_RUNTIME, reason)
                attempts++
                if (attempts == 1) {
                    error("Cleanup unavailable")
                }
            }
        }
        val job = runningJob(spec, 0L, 20_000L, maxRuntimeSeconds = 10)
        monitor(5, spec).sweep(20_000L)
        assertEquals(JobState.RUNNING, load(job).state)
        monitor(5, spec).sweep(20_000L)
        assertEquals(JobState.TIMED_OUT, load(job).state)
        assertEquals(2, attempts)
    }

    @Test
    fun `atomic timeout guard rechecks heartbeat and runtime independently`() {
        val spec = TimeoutSpec("timeout-guard")
        val job = runningJob(spec, 0L, 20_000L)
        assertNull(db { JobRecordsTable.tryMarkTimedOut(job, 10_000L, 20_000L) })
        assertNotNull(db {
            JobRecordsTable.tryMarkTimedOut(job, 10_000L, 20_000L, reason = JobTimeoutReason.MAX_RUNTIME)
        })
        assertNull(db {
            JobRecordsTable.tryMarkTimedOut(job, 10_000L, 20_000L, reason = JobTimeoutReason.MAX_RUNTIME)
        })
    }

    @Test
    fun `job limits survive copying retry inheritance and explicit disable`() {
        val spec = TimeoutSpec("copy-limits", timeoutSeconds = 30, maxRuntimeSeconds = 60)
        val parentId = JobRecordsTable.insertJob(spec, timeoutSeconds = 10, maxRuntimeSeconds = 20)
        val parent = load(parentId)
        val copy = JobRecordsTable.launchCopy(parent)
        assertEquals(10, copy.timeoutSeconds)
        assertEquals(20, copy.maxRuntimeSeconds)

        val job = object : Job {
            override fun execute(context: JobContext): JobResult = JobCompleted()
        }
        val context = DispatchContext(1, job, spec, parent, JobRecordsTable, null)
        val retry = load(JobRecordsTable.insertJob(spec, parentJobContext = context))
        assertEquals(10, retry.timeoutSeconds)
        assertEquals(20, retry.maxRuntimeSeconds)
        val disabled = load(JobRecordsTable.insertJob(spec, parentJobContext = context, timeoutSeconds = 0, maxRuntimeSeconds = 0))
        assertEquals(0, disabled.timeoutSeconds)
        assertEquals(0, disabled.maxRuntimeSeconds)
    }

    @Test
    fun `failed callback rolls back timeout and retries after monitor restart`() {
        val now = 20_000L
        var attempts = 0
        val spec = object : JobSpec {
            override val jobTypeIdentifier = "retry-timeout-callback"
            override fun createJob(): Job = error("Not dispatched")

            override fun onJobTimedOut(jobRecord: JobRecord, timedOutAt: Long, timeoutSeconds: Int) {
                attempts++
                db { JobRecordsTable.updateJobDetails(jobRecord.id, json { "cleaned" set true }) }
                if (attempts == 1) {
                    error("Callback failed after updating application state")
                }
            }
        }
        val jobId = runningJob(spec, startedAt = 0L, lastCheckInAt = null)

        monitor(10, spec).sweep(now)

        val pending = load(jobId)
        assertEquals(JobState.RUNNING, pending.state)
        assertNull(pending.endedAt)
        assertNull(pending.failureDetails)
        assertNull(pending.jobDetails)

        // Retry must survive losing all in-memory monitor state.
        val restarted = monitor(10, spec)
        restarted.sweep(now)
        restarted.sweep(now)

        assertEquals(2, attempts)
        assertEquals(JobState.TIMED_OUT, load(jobId).state)
        assertEquals(true, load(jobId).jobDetails?.get("cleaned")?.boolean)
    }

    @Test
    fun `failed timeout callback does not block other expired jobs`() {
        val failedSpec = object : JobSpec {
            override val jobTypeIdentifier = "failed-timeout-callback"
            override fun createJob(): Job = error("Not dispatched")
            override fun onJobTimedOut(jobRecord: JobRecord, timedOutAt: Long, timeoutSeconds: Int) {
                error("Callback unavailable")
            }
        }
        val healthySpec = TimeoutSpec("healthy-timeout-callback")
        val failedId = runningJob(failedSpec, startedAt = 0L, lastCheckInAt = null)
        val healthyId = runningJob(healthySpec, startedAt = 0L, lastCheckInAt = null)

        monitor(10, failedSpec, healthySpec).sweep(20_000L)

        assertEquals(JobState.RUNNING, load(failedId).state)
        assertEquals(JobState.TIMED_OUT, load(healthyId).state)
        assertEquals(healthyId, healthySpec.timedOutJob?.id)
    }

    @Test
    fun `short heartbeat limits reduce check-in throttling`() {
        val spec = TimeoutSpec("short-heartbeat", timeoutSeconds = 2)
        for (override in listOf<Int?>(null, 1, 0)) {
            val id = runningJob(spec, 100_000L, null, timeoutSeconds = override)
            val context = DispatchContext(
                30,
                object : Job { override fun execute(context: JobContext): JobResult = JobCompleted() },
                spec, load(id), JobRecordsTable, null, startedAt = 100_000L,
            )
            val interval = when (override) { 1 -> 500L; 0 -> 30_000L; else -> 1_000L }
            context.checkIn(100_000L + interval - 1)
            assertNull(load(id).lastCheckInAt)
            context.checkIn(100_000L + interval)
            assertEquals(100_000L + interval, load(id).lastCheckInAt)
        }
    }

    private fun monitor(defaultTimeoutSeconds: Int, vararg specs: JobSpec, maxRuntimeSeconds: Int = 0): JobTimeoutMonitor {
        val registry = JobRegistry()
        specs.forEach { registry += it }
        val configuration = object : BaseJobConfiguration() {
            override val defaultJobTimeoutSeconds: Int = defaultTimeoutSeconds
            override val defaultJobMaxRuntimeSeconds: Int = maxRuntimeSeconds
        }
        return JobTimeoutMonitor(
            configuration = JobConfigurationGuard(configuration),
            registry = registry,
            databaseGatekeeper = DatabaseGatekeeper(configuration),
            jobsTable = JobRecordsTable,
        )
    }

    private fun runningJob(spec: JobSpec, startedAt: Long, lastCheckInAt: Long?, timeoutSeconds: Int? = null, maxRuntimeSeconds: Int? = null): Long {
        val id = JobRecordsTable.insertJob(spec, timeoutSeconds = timeoutSeconds, maxRuntimeSeconds = maxRuntimeSeconds)
        db { JobRecordsTable.tryCaptureRunLock(id, "test-system", spec) }

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE job_records SET started_at = ?, last_checkin_at = ? WHERE id = ?").use { stmt ->
                stmt.setLong(1, startedAt)
                if (lastCheckInAt == null) {
                    stmt.setNull(2, java.sql.Types.BIGINT)
                } else {
                    stmt.setLong(2, lastCheckInAt)
                }
                stmt.setLong(3, id)
                stmt.executeUpdate()
            }
        }

        return id
    }

    private fun load(jobId: Long): JobRecord {
        return assertNotNull(db { JobRecordsTable.findById(jobId) })
    }

    private class TimeoutSpec(
        override val jobTypeIdentifier: String,
        override val timeoutSeconds: Int? = null,
        override val maxRuntimeSeconds: Int? = null,
    ) : JobSpec {
        var timedOutJob: JobRecord? = null
            private set
        var timedOutAt: Long? = null
            private set
        var timedOutAfterSeconds: Int? = null
            private set
        var reason: JobTimeoutReason? = null
            private set

        override fun createJob(): Job {
            throw UnsupportedOperationException()
        }

        override fun onJobTimedOut(jobRecord: JobRecord, timedOutAt: Long, timeoutSeconds: Int) {
            this.timedOutJob = jobRecord
            this.timedOutAt = timedOutAt
            this.timedOutAfterSeconds = timeoutSeconds
        }

        override fun onJobTimedOut(jobRecord: JobRecord, timedOutAt: Long, timeoutSeconds: Int, reason: JobTimeoutReason) {
            this.reason = reason
            onJobTimedOut(jobRecord, timedOutAt, timeoutSeconds)
        }
    }
}
