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

    private fun monitor(defaultTimeoutSeconds: Int, vararg specs: JobSpec): JobTimeoutMonitor {
        val registry = JobRegistry()
        specs.forEach { registry += it }
        val configuration = object : BaseJobConfiguration() {
            override val defaultJobTimeoutSeconds: Int = defaultTimeoutSeconds
        }
        return JobTimeoutMonitor(
            configuration = JobConfigurationGuard(configuration),
            registry = registry,
            databaseGatekeeper = DatabaseGatekeeper(configuration),
            jobsTable = JobRecordsTable,
        )
    }

    private fun runningJob(spec: JobSpec, startedAt: Long, lastCheckInAt: Long?): Long {
        val id = JobRecordsTable.insertJob(spec)
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
    ) : JobSpec {
        var timedOutJob: JobRecord? = null
            private set
        var timedOutAt: Long? = null
            private set
        var timedOutAfterSeconds: Int? = null
            private set

        override fun createJob(): Job {
            throw UnsupportedOperationException()
        }

        override fun onJobTimedOut(jobRecord: JobRecord, timedOutAt: Long, timeoutSeconds: Int) {
            this.timedOutJob = jobRecord
            this.timedOutAt = timedOutAt
            this.timedOutAfterSeconds = timeoutSeconds
        }
    }
}
