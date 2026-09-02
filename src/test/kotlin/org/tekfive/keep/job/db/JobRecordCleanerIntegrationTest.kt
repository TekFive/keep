package org.tekfive.keep.job.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.db.DbConnection
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobLogger
import org.tekfive.keep.job.JobState
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

@Testcontainers
class JobRecordCleanerIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @BeforeAll
        @JvmStatic
        fun initSchema() {
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
        fun tearDown() {
            DbConnection.shutdown()
            AckRegistry.clear()
        }
    }

    @BeforeEach
    fun cleanUp() {
        PostgresTestSupport.truncateJobsTable(postgres)
    }

    private class RecordingJobContext(job: Job) : JobContext {
        var checkIns = 0
        override val jobId = 1L
        override val startedAt = System.currentTimeMillis()
        override val type = "job-record-cleaner"
        override val createdAt = System.currentTimeMillis()
        override val attempt = 1
        override val maxRetries = 0
        override val estimatedRuntimeSeconds: Int? = null
        override val details: JsonObject? = null
        override val log: JobLogger = JobLogger(job, this, null)

        override fun checkIn(now: Long) {
            checkIns++
        }

        override fun updateDetails(details: JsonObject) = Unit
    }

    /** Bulk-inserts [count] job records of [type] with the given terminal state and end time. */
    private fun insertEndedJobs(type: String, count: Int, state: JobState, endedAt: Long) {
        PostgresTestSupport.getConnection(postgres).use { conn ->
            val sql = """
                INSERT INTO job_records (type, created_at, priority, attempt, state, started_at, ended_at)
                SELECT ?, ?, 0, 1, ?, ?, ?
                FROM generate_series(1, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, type)
                stmt.setLong(2, endedAt - 1000)
                stmt.setInt(3, state.id)
                stmt.setLong(4, endedAt - 1000)
                stmt.setLong(5, endedAt)
                stmt.setInt(6, count)
                stmt.executeUpdate()
            }
        }
    }

    /** Adds one log line to every job record of [type]. */
    private fun insertLogsForType(type: String) {
        PostgresTestSupport.getConnection(postgres).use { conn ->
            val sql = """
                INSERT INTO job_record_logs (job_record_id, level, message, added_at)
                SELECT id, ?, 'log line', 1
                FROM job_records WHERE type = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, JobRecordLogLevel.INFO.id)
                stmt.setString(2, type)
                stmt.executeUpdate()
            }
        }
    }

    private fun countRecords(type: String): Int = count(
        "SELECT count(*) FROM job_records WHERE type = ?",
        type,
    )

    private fun countLogs(type: String): Int = count(
        "SELECT count(*) FROM job_record_logs l JOIN job_records r ON r.id = l.job_record_id WHERE r.type = ?",
        type,
    )

    private fun countAllLogs(): Int = count("SELECT count(*) FROM job_record_logs", null)

    private fun count(sql: String, argument: String?): Int {
        PostgresTestSupport.getConnection(postgres).use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                if (argument != null) stmt.setString(1, argument)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    @Test
    fun `execute deletes aged records and their logs in batches and keeps recent ones`() {
        val now = System.currentTimeMillis()
        val longAgo = now - 60.days.inWholeMilliseconds

        // More records than one batch so the batching loop is exercised.
        insertEndedJobs("old-completed", 2_100, JobState.COMPLETED, longAgo)
        insertEndedJobs("old-failed", 400, JobState.FAILED, longAgo)
        insertEndedJobs("recent-completed", 5, JobState.COMPLETED, now)
        insertLogsForType("old-completed")
        insertLogsForType("recent-completed")

        assertEquals(2_105, countAllLogs())

        val cleaner = JobRecordCleaner()
        val context = RecordingJobContext(cleaner)
        cleaner.execute(context)

        assertEquals(0, countRecords("old-completed"))
        assertEquals(0, countRecords("old-failed"))
        assertEquals(5, countRecords("recent-completed"))
        assertEquals(5, countLogs("recent-completed"))
        assertEquals(5, countAllLogs())
        assertTrue(
            context.checkIns >= 2,
            "Expected a check-in between batches of ${JobRecordCleaner.PURGE_BATCH_SIZE}, got ${context.checkIns}",
        )
    }

    @Test
    fun `execute keeps records that never ended`() {
        val now = System.currentTimeMillis()
        insertEndedJobs("old-completed", 3, JobState.COMPLETED, now - 60.days.inWholeMilliseconds)

        PostgresTestSupport.getConnection(postgres).use { conn ->
            conn.prepareStatement(
                "INSERT INTO job_records (type, created_at, priority, attempt, state) VALUES ('running-job', 1, 0, 1, ?)"
            ).use { stmt ->
                stmt.setInt(1, JobState.RUNNING.id)
                stmt.executeUpdate()
            }
        }

        val cleaner = JobRecordCleaner()
        cleaner.execute(RecordingJobContext(cleaner))

        assertEquals(0, countRecords("old-completed"))
        assertEquals(1, countRecords("running-job"))
    }
}
