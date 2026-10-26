package org.tekfive.keep.job.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import org.tekfive.keep.db.DbConnection
import org.tekfive.keep.db.db
import org.tekfive.keep.db.dbConnection
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobSpec
import org.tekfive.keep.job.JobState
import org.tekfive.keep.job.JobTimeoutReason
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class JobStateTransitionsIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @BeforeAll
        @JvmStatic
        fun setup() {
            PostgresTestSupport.initSchema(postgres)
            AckRegistry.clear()
            AckRegistry.addSource(MapSource(mapOf(
                "JDBC_URL" to postgres.jdbcUrl,
                "JDBC_USER" to postgres.username,
                "JDBC_PASSWORD" to postgres.password,
            )))
            DbConnection.startup()
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            DbConnection.shutdown()
            AckRegistry.clear()
        }
    }

    @BeforeEach
    fun cleanUp() = PostgresTestSupport.truncateJobsTable(postgres)

    private val spec = object : JobSpec {
        override val jobTypeIdentifier = "history-test"
        override val jobDescription = "Test job for state history."
        override fun createJob(): Job = error("Not dispatched")
    }

    @Test
    fun `history records creation capture and completion with outcome snapshots`() {
        val before = System.currentTimeMillis()
        val id = JobRecordsTable.insertJob(spec)
        val job = assertNotNull(JobRecordsTable.tryCaptureRunLock(id, "worker-a", spec, "app-v1"))
        JobRecordsTable.updateProgress(id, 7, 1, 10)
        JobRecordsTable.updateLastCheckIn(id, System.currentTimeMillis())
        assertEquals(2, JobStateTransitionsTable.getForJob(id).size)
        assertTrue(JobRecordsTable.tryMarkEnded(id, System.currentTimeMillis(), JobState.COMPLETED,
            resultSummary = "Finished", processedCount = 10))
        assertFalse(JobRecordsTable.tryMarkEnded(id, System.currentTimeMillis(), JobState.FAILED))

        val history = JobStateTransitionsTable.getForJob(id)
        assertEquals(listOf(null, JobState.PENDING, JobState.RUNNING), history.map { it.fromState })
        assertEquals(listOf(JobState.PENDING, JobState.RUNNING, JobState.COMPLETED), history.map { it.toState })
        assertTrue(history.all { it.transitionedAt in before..System.currentTimeMillis() })
        assertNull(history.first().systemIdentifier)
        val completed = history.last()
        assertEquals("worker-a", completed.systemIdentifier)
        assertEquals("app-v1", completed.applicationVersion)
        assertEquals("Finished", completed.resultSummary)
        assertEquals(10L, completed.processedCount)
        assertEquals(1L, completed.failedCount)
        assertEquals(10L, completed.totalCount)
        JobRecordsTable.insertJob(spec)
        assertEquals(history.map { it.id }, JobStateTransitionsTable.getForRun(job.runId).map { it.id })
    }

    @Test
    fun `requeue and orphan recovery retain the previous worker and progress`() {
        val id = JobRecordsTable.insertJob(spec)
        JobRecordsTable.tryCaptureRunLock(id, "worker-a", spec, "app-v1")
        JobRecordsTable.updateProgress(id, 4, 1, 10)
        assertTrue(JobRecordsTable.tryRequeue(id))
        assertFalse(JobRecordsTable.tryRequeue(id))
        JobRecordsTable.tryCaptureRunLock(id, "worker-b", spec, "app-v2")
        JobRecordsTable.requeueOrphanedRunningJobs("worker-b", Long.MAX_VALUE)

        val history = JobStateTransitionsTable.getForJob(id)
        assertEquals(listOf(JobState.PENDING, JobState.RUNNING, JobState.PENDING, JobState.RUNNING, JobState.PENDING),
            history.map { it.toState })
        assertEquals("worker-a", history[2].systemIdentifier)
        assertEquals(4L, history[2].processedCount)
        assertEquals("worker-b", history.last().systemIdentifier)
        assertEquals("app-v2", history.last().applicationVersion)
        assertNull(history[3].processedCount)
    }

    @Test
    fun `direct SQL cancellation is recorded and unchanged state is ignored`() {
        val id = JobRecordsTable.insertJob(spec)
        PostgresTestSupport.getConnection(postgres).use { connection ->
            connection.prepareStatement("UPDATE job_records SET state = ?, result_summary = 'Cancelled by operator' WHERE id = ?").use {
                it.setInt(1, JobState.CANCELLED.id)
                it.setLong(2, id)
                it.executeUpdate()
                it.executeUpdate()
            }
        }
        val history = JobStateTransitionsTable.getForJob(id)
        assertEquals(2, history.size)
        assertEquals(JobState.PENDING, history.last().fromState)
        assertEquals(JobState.CANCELLED, history.last().toState)
        assertEquals("Cancelled by operator", history.last().resultSummary)
    }

    @Test
    fun `rolling back a state change or insertion also rolls back history`() {
        val id = JobRecordsTable.insertJob(spec)
        var rolledBackId = 0L
        assertFailsWith<IllegalStateException> {
            db {
                JobRecordsTable.tryCaptureRunLock(id, "worker", spec)
                assertEquals(2, JobStateTransitionsTable.getForJob(id).size)
                rolledBackId = JobRecordsTable.insertJob(spec)
                assertEquals(1, JobStateTransitionsTable.getForJob(rolledBackId).size)
                error("Roll back both operations")
            }
        }
        assertEquals(JobState.PENDING, db { JobRecordsTable.getById(id) }.state)
        assertEquals(1, JobStateTransitionsTable.getForJob(id).size)
        assertNull(db { JobRecordsTable.findById(rolledBackId) })
        assertTrue(JobStateTransitionsTable.getForJob(rolledBackId).isEmpty())
    }

    @Test
    fun `failed history write prevents the state change from committing`() {
        val id = JobRecordsTable.insertJob(spec)
        db { dbConnection().createStatement().use { it.execute("ALTER TABLE job_state_transitions ADD CONSTRAINT reject_running CHECK (to_state <> 2)") } }
        try {
            assertFailsWith<SQLException> { JobRecordsTable.tryCaptureRunLock(id, "worker", spec) }
            assertEquals(JobState.PENDING, db { JobRecordsTable.getById(id) }.state)
            assertEquals(1, JobStateTransitionsTable.getForJob(id).size)
        } finally {
            db { dbConnection().createStatement().use { it.execute("ALTER TABLE job_state_transitions DROP CONSTRAINT reject_running") } }
        }
    }

    @Test
    fun `timeout history snapshots the reason and job deletion cascades to history`() {
        val id = JobRecordsTable.insertJob(spec)
        JobRecordsTable.tryCaptureRunLock(id, "worker", spec)
        JobRecordsTable.updateProgress(id, 4, 1, 10)
        assertNotNull(JobRecordsTable.tryMarkTimedOut(id, Long.MAX_VALUE, System.currentTimeMillis(),
            "Runtime expired", JobTimeoutReason.MAX_RUNTIME))
        val timeout = JobStateTransitionsTable.getForJob(id).last()
        assertEquals(JobState.TIMED_OUT, timeout.toState)
        assertEquals(JobTimeoutReason.MAX_RUNTIME, timeout.timeoutReason)
        assertEquals("Runtime expired", timeout.resultSummary)
        assertEquals(4L, timeout.processedCount)
        PostgresTestSupport.getConnection(postgres).use { connection ->
            connection.prepareStatement("DELETE FROM job_records WHERE id = ?").use {
                it.setLong(1, id)
                assertEquals(1, it.executeUpdate())
            }
        }
        assertTrue(JobStateTransitionsTable.getForJob(id).isEmpty())
    }
}
