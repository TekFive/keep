package org.tekfive.keep.job.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import org.tekfive.keep.db.DbConnection
import org.tekfive.keep.db.db
import org.tekfive.keep.job.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class JobRecordsTableOperationsIntegrationTest {

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
        DbConnection.clock = Clock.systemUTC()
    }

    private val table = JobRecordsTable

    private val defaultSpec = object : JobSpec {
        override val jobTypeIdentifier = "test-job"
        override fun createJob(): Job = throw UnsupportedOperationException()
    }

    private val exclusiveSpec = object : JobSpec {
        override val jobTypeIdentifier = "test-job"
        override val exclusiveExecution = true
        override fun createJob(): Job = throw UnsupportedOperationException()
    }

    private val typeLimitedSpec = object : JobSpec {
        override val jobTypeIdentifier = "test-job"
        override val maxConcurrentJobs = 2
        override fun createJob(): Job = throw UnsupportedOperationException()
    }

    private fun insertWaitingJob(
        type: String = "test-job",
        priority: Int = 0,
        minimumStartAt: Long? = null,
        createdAt: Long = System.currentTimeMillis(),
        lockKey: String? = null,
        maxConcurrentJobs: Int? = null,
        concurrencyKey: String? = null,
    ): Long {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val sql = """
                INSERT INTO job_records (
                    type, created_at, priority, attempt, state, minimum_start_at, lock_key,
                    max_concurrent_jobs, concurrency_key
                )
                VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?)
                RETURNING id
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, type)
                stmt.setLong(2, createdAt)
                stmt.setInt(3, priority)
                stmt.setInt(4, JobState.PENDING.id)
                if (minimumStartAt != null) stmt.setLong(5, minimumStartAt) else stmt.setNull(5, java.sql.Types.BIGINT)
                if (lockKey != null) stmt.setString(6, lockKey) else stmt.setNull(6, java.sql.Types.VARCHAR)
                if (maxConcurrentJobs != null) stmt.setInt(7, maxConcurrentJobs) else stmt.setNull(7, java.sql.Types.INTEGER)
                if (concurrencyKey != null) stmt.setString(8, concurrencyKey) else stmt.setNull(8, java.sql.Types.VARCHAR)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    private fun markRunning(jobId: Long) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE job_records SET state = ?, started_at = ? WHERE id = ?").use { stmt ->
                stmt.setInt(1, JobState.RUNNING.id)
                stmt.setLong(2, System.currentTimeMillis())
                stmt.setLong(3, jobId)
                stmt.executeUpdate()
            }
        }
    }

    // --- getJobIdStartCandidates ---

    @Test
    fun `getJobIdStartCandidates returns waiting jobs matching type IDs`() {
        val id1 = insertWaitingJob(type = "type-a")
        val id2 = insertWaitingJob(type = "type-b")
        insertWaitingJob(type = "type-c") // should not be returned

        val candidates = db {  table.getJobIdStartCandidates(10, listOf("type-a", "type-b"), System.currentTimeMillis()) }
        val candidateIds = candidates.map { it.first }
        assertTrue(candidateIds.contains(id1))
        assertTrue(candidateIds.contains(id2))
        assertEquals(2, candidates.size)
    }

    @Test
    fun `getJobIdStartCandidates orders by priority DESC then created_at ASC`() {
        val now = System.currentTimeMillis()
        val lowPriOld = insertWaitingJob(priority = 1, createdAt = now - 2000)
        val highPri = insertWaitingJob(priority = 10, createdAt = now)
        val lowPriNew = insertWaitingJob(priority = 1, createdAt = now - 1000)

        val candidates = db {  table.getJobIdStartCandidates(10, listOf("test-job"), System.currentTimeMillis()) }
        assertEquals(3, candidates.size)
        assertEquals(highPri, candidates[0].first) // highest priority first
        assertEquals(lowPriOld, candidates[1].first) // same priority, older first
        assertEquals(lowPriNew, candidates[2].first)
    }

    @Test
    fun `getJobIdStartCandidates filters out jobs with future minimum_start_at`() {
        val now = System.currentTimeMillis()
        DbConnection.clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneId.of("UTC"))

        val ready = insertWaitingJob(minimumStartAt = now - 1000)
        insertWaitingJob(minimumStartAt = now + 60_000) // future, should be excluded

        val candidates = db {  table.getJobIdStartCandidates(10, listOf("test-job"), now) }
        assertEquals(1, candidates.size)
        assertEquals(ready, candidates[0].first)
    }

    @Test
    fun `getJobIdStartCandidates includes jobs with null minimum_start_at`() {
        val id = insertWaitingJob(minimumStartAt = null)

        val candidates = db {  table.getJobIdStartCandidates(10, listOf("test-job"), System.currentTimeMillis()) }
        assertEquals(1, candidates.size)
        assertEquals(id, candidates[0].first)
    }

    @Test
    fun `getJobIdStartCandidates returns empty for empty type IDs list`() {
        insertWaitingJob()

        val candidates = db {  table.getJobIdStartCandidates(10, emptyList(), System.currentTimeMillis()) }
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `getJobIdStartCandidates respects limit`() {
        insertWaitingJob()
        insertWaitingJob()
        insertWaitingJob()

        val candidates = db {  table.getJobIdStartCandidates(2, listOf("test-job"), System.currentTimeMillis()) }
        assertEquals(2, candidates.size)
    }

    @Test
    fun `getJobIdStartCandidates skips a locked job and returns a later job of another type`() {
        val now = System.currentTimeMillis()
        markRunning(insertWaitingJob(type = "locked-type", lockKey = "lock-a", createdAt = now - 3000))
        insertWaitingJob(type = "locked-type", lockKey = "lock-a", createdAt = now - 2000)
        val other = insertWaitingJob(type = "other-type", createdAt = now - 1000)

        val candidates = db { table.getJobIdStartCandidates(10, listOf("locked-type", "other-type"), now) }

        assertEquals(listOf(other), candidates.map { it.first })
    }

    @Test
    fun `getJobIdStartCandidates returns a job whose lock key is free`() {
        val now = System.currentTimeMillis()
        markRunning(insertWaitingJob(type = "locked-type", lockKey = "lock-a", createdAt = now - 3000))
        val free = insertWaitingJob(type = "locked-type", lockKey = "lock-b", createdAt = now - 2000)

        val candidates = db { table.getJobIdStartCandidates(10, listOf("locked-type"), now) }

        assertEquals(listOf(free), candidates.map { it.first })
    }

    @Test
    fun `getJobIdStartCandidates skips a saturated concurrency scope`() {
        val now = System.currentTimeMillis()
        markRunning(insertWaitingJob(maxConcurrentJobs = 2, concurrencyKey = "account-a", createdAt = now - 5000))
        markRunning(insertWaitingJob(maxConcurrentJobs = 2, concurrencyKey = "account-a", createdAt = now - 4000))
        insertWaitingJob(maxConcurrentJobs = 2, concurrencyKey = "account-a", createdAt = now - 3000)
        val unsaturated = insertWaitingJob(maxConcurrentJobs = 2, concurrencyKey = "account-b", createdAt = now - 2000)

        val candidates = db { table.getJobIdStartCandidates(10, listOf("test-job"), now) }

        assertEquals(listOf(unsaturated), candidates.map { it.first })
    }

    @Test
    fun `getJobIdStartCandidates counts every running job of the type when the candidate has no concurrency key`() {
        val now = System.currentTimeMillis()
        markRunning(insertWaitingJob(maxConcurrentJobs = 2, concurrencyKey = "account-a", createdAt = now - 5000))
        markRunning(insertWaitingJob(maxConcurrentJobs = 2, createdAt = now - 4000))
        insertWaitingJob(maxConcurrentJobs = 2, createdAt = now - 3000)

        val candidates = db { table.getJobIdStartCandidates(10, listOf("test-job"), now) }

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `getJobIdStartCandidates ignores a null max concurrent jobs`() {
        val now = System.currentTimeMillis()
        markRunning(insertWaitingJob(createdAt = now - 3000))
        val pending = insertWaitingJob(createdAt = now - 2000)

        val candidates = db { table.getJobIdStartCandidates(10, listOf("test-job"), now) }

        assertEquals(listOf(pending), candidates.map { it.first })
    }

    @Test
    fun `getJobIdStartCandidates omits excluded ids`() {
        val now = System.currentTimeMillis()
        val queued = insertWaitingJob(createdAt = now - 3000)
        val next = insertWaitingJob(createdAt = now - 2000)

        val candidates = db { table.getJobIdStartCandidates(10, listOf("test-job"), now, excludeIds = listOf(queued)) }

        assertEquals(listOf(next), candidates.map { it.first })
    }

    // --- tryCaptureRunLock ---

    @Test
    fun `tryCaptureRunLock captures lock on WAITING job and returns RUNNING record`() {
        val id = insertWaitingJob()

        val job = db {  table.tryCaptureRunLock(id, "my-system", defaultSpec) }
        assertNotNull(job)
        assertEquals(id, job.id)
        assertEquals(JobState.RUNNING, job.state)
        assertEquals("my-system", job.systemIdentifier)
        assertNotNull(job.startedAt)
    }

    @Test
    fun `tryCaptureRunLock returns null for already RUNNING job`() {
        val id = insertWaitingJob()

        // First capture succeeds
        assertNotNull(db {  table.tryCaptureRunLock(id, "test-system", defaultSpec) })

        // Second capture fails (already RUNNING)
        assertNull(db {  table.tryCaptureRunLock(id, "test-system", defaultSpec) })
    }

    @Test
    fun `tryCaptureRunLock returns null for nonexistent ID`() {
        assertNull(db {  table.tryCaptureRunLock(99999L, "test-system", defaultSpec) })
    }

    @Test
    fun `tryCaptureRunLock blocks when exclusive and another of same type is running`() {
        val running = insertWaitingJob()
        markRunning(running)
        val waiting = insertWaitingJob()

        assertNull(db {  table.tryCaptureRunLock(waiting, "test-system", exclusiveSpec) })
    }

    @Test
    fun `tryCaptureRunLock succeeds when exclusive and no other of same type is running`() {
        val waiting = insertWaitingJob()

        assertNotNull(db {  table.tryCaptureRunLock(waiting, "test-system", exclusiveSpec) })
    }

    @Test
    fun `tryCaptureRunLock blocks when same type and lock key is running`() {
        val running = insertWaitingJob(lockKey = "key-a")
        markRunning(running)
        val waiting = insertWaitingJob(lockKey = "key-a")

        assertNull(db {  table.tryCaptureRunLock(waiting, "test-system", defaultSpec) })
    }

    @Test
    fun `tryCaptureRunLock succeeds when same type but different lock key is running`() {
        val running = insertWaitingJob(lockKey = "key-a")
        markRunning(running)
        val waiting = insertWaitingJob(lockKey = "key-b")

        assertNotNull(db {  table.tryCaptureRunLock(waiting, "test-system", defaultSpec) })
    }

    @Test
    fun `tryCaptureRunLock succeeds when no lock key and no exclusive`() {
        val running = insertWaitingJob()
        markRunning(running)
        val waiting = insertWaitingJob()

        assertNotNull(db {  table.tryCaptureRunLock(waiting, "test-system", defaultSpec) })
    }

    @Test
    fun `tryCaptureRunLock exclusive ignores lock key on record`() {
        val running = insertWaitingJob(lockKey = "key-a")
        markRunning(running)
        val waiting = insertWaitingJob(lockKey = "key-b")

        assertNull(db {  table.tryCaptureRunLock(waiting, "test-system", exclusiveSpec) })
    }

    @Test
    fun `tryCaptureRunLock allows type-level concurrent jobs below max`() {
        val running = insertWaitingJob(maxConcurrentJobs = 2)
        markRunning(running)
        val waiting = insertWaitingJob(maxConcurrentJobs = 2)

        assertNotNull(db { table.tryCaptureRunLock(waiting, "test-system", defaultSpec) })
    }

    @Test
    fun `tryCaptureRunLock blocks type-level concurrent jobs at max`() {
        val running1 = insertWaitingJob(maxConcurrentJobs = 2)
        val running2 = insertWaitingJob(maxConcurrentJobs = 2)
        markRunning(running1)
        markRunning(running2)
        val waiting = insertWaitingJob(maxConcurrentJobs = 2)

        assertNull(db { table.tryCaptureRunLock(waiting, "test-system", defaultSpec) })
    }

    @Test
    fun `tryCaptureRunLock applies spec type-level concurrent jobs max`() {
        val running1 = insertWaitingJob()
        val running2 = insertWaitingJob()
        markRunning(running1)
        markRunning(running2)
        val waiting = insertWaitingJob()

        assertNull(db { table.tryCaptureRunLock(waiting, "test-system", typeLimitedSpec) })
    }

    @Test
    fun `tryCaptureRunLock blocks keyed concurrent jobs at max`() {
        val running1 = insertWaitingJob(maxConcurrentJobs = 2, concurrencyKey = "account-a")
        val running2 = insertWaitingJob(maxConcurrentJobs = 2, concurrencyKey = "account-a")
        markRunning(running1)
        markRunning(running2)
        val waiting = insertWaitingJob(maxConcurrentJobs = 2, concurrencyKey = "account-a")

        assertNull(db { table.tryCaptureRunLock(waiting, "test-system", defaultSpec) })
    }

    @Test
    fun `tryCaptureRunLock allows keyed concurrent jobs for different key`() {
        val running1 = insertWaitingJob(maxConcurrentJobs = 2, concurrencyKey = "account-a")
        val running2 = insertWaitingJob(maxConcurrentJobs = 2, concurrencyKey = "account-a")
        markRunning(running1)
        markRunning(running2)
        val waiting = insertWaitingJob(maxConcurrentJobs = 2, concurrencyKey = "account-b")

        assertNotNull(db { table.tryCaptureRunLock(waiting, "test-system", defaultSpec) })
    }

    @Test
    fun `insertJob stores concurrency limit and key`() {
        val id = db {
            table.insertJob(
                defaultSpec,
                maxConcurrentJobs = 3,
                concurrencyKey = "tenant-a",
            )
        }

        val job = db { table.getById(id) }
        assertEquals(3, job.maxConcurrentJobs)
        assertEquals("tenant-a", job.concurrencyKey)
    }

    @Test
    fun `insertJob stores an explicit parent job id`() {
        val id = db {
            table.insertJob(
                defaultSpec,
                parentJobId = 987L,
            )
        }

        val job = db { table.getById(id) }
        assertEquals(987L, job.parentJobId)
    }

    @Test
    fun `insertJob rejects concurrency key without max`() {
        assertFailsWith<IllegalArgumentException> {
            db {
                table.insertJob(
                    defaultSpec,
                    concurrencyKey = "tenant-a",
                )
            }
        }
    }

    @Test
    fun `insertJob rejects non-positive max concurrent jobs`() {
        assertFailsWith<IllegalArgumentException> {
            db {
                table.insertJob(
                    defaultSpec,
                    maxConcurrentJobs = 0,
                )
            }
        }
    }

    @Test
    fun `database unique index prevents multiple running jobs with same type and lock key`() {
        val sql = """
            INSERT INTO job_records (type, created_at, priority, attempt, state, lock_key, started_at)
            VALUES (?, ?, 0, 1, ?, ?, ?)
        """.trimIndent()

        PostgresTestSupport.getConnection(postgres).use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, "keyed-type")
                stmt.setLong(2, 1_000L)
                stmt.setInt(3, JobState.RUNNING.id)
                stmt.setString(4, "lock-a")
                stmt.setLong(5, 1_000L)
                stmt.executeUpdate()
            }

            val exception = assertFailsWith<SQLException> {
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, "keyed-type")
                    stmt.setLong(2, 2_000L)
                    stmt.setInt(3, JobState.RUNNING.id)
                    stmt.setString(4, "lock-a")
                    stmt.setLong(5, 2_000L)
                    stmt.executeUpdate()
                }
            }

            assertEquals("23505", exception.sqlState)
        }
    }

    @Test
    fun `database unique index prevents multiple non-terminated scheduled jobs of same type`() {
        val sql = """
            INSERT INTO job_records (type, created_at, priority, attempt, state, scheduled_job, minimum_start_at)
            VALUES (?, ?, 0, 1, ?, ?, ?)
        """.trimIndent()

        PostgresTestSupport.getConnection(postgres).use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, "scheduled-type")
                stmt.setLong(2, 1_000L)
                stmt.setInt(3, JobState.PENDING.id)
                stmt.setBoolean(4, true)
                stmt.setLong(5, 5_000L)
                stmt.executeUpdate()
            }

            val exception = assertFailsWith<SQLException> {
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, "scheduled-type")
                    stmt.setLong(2, 2_000L)
                    stmt.setInt(3, JobState.PENDING.id)
                    stmt.setBoolean(4, true)
                    stmt.setLong(5, 6_000L)
                    stmt.executeUpdate()
                }
            }

            assertEquals("23505", exception.sqlState)
        }
    }

    // --- markEnded ---

    @Test
    fun `markEnded sets state, ended_at, and failure_details`() {
        val id = insertWaitingJob()
        db {  table.tryCaptureRunLock(id, "test-system", defaultSpec) }

        val endedAt = System.currentTimeMillis()
        db {  table.markEnded(id, endedAt, JobState.FAILED) }

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT state, ended_at, failure_details FROM job_records WHERE id = ?").use { stmt ->
                stmt.setLong(1, id)
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(JobState.FAILED.id, rs.getInt("state"))
                    assertEquals(endedAt, rs.getLong("ended_at"))
                }
            }
        }
    }

    @Test
    fun `markEnded sets failure_details NULL when null passed`() {
        val id = insertWaitingJob()
        db {  table.tryCaptureRunLock(id, "test-system", defaultSpec) }

        db {  table.markEnded(id, System.currentTimeMillis(), JobState.COMPLETED) }

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT state, failure_details FROM job_records WHERE id = ?").use { stmt ->
                stmt.setLong(1, id)
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(JobState.COMPLETED.id, rs.getInt("state"))
                    assertNull(rs.getString("failure_details"))
                }
            }
        }
    }

    // --- updateLastCheckIn ---

    @Test
    fun `updateLastCheckIn updates last_checkin_at and returns current state`() {
        val id = insertWaitingJob()
        db {  table.tryCaptureRunLock(id, "test-system", defaultSpec) }

        val checkInAt = System.currentTimeMillis()
        val state = db {  table.updateLastCheckIn(id, checkInAt) }

        assertEquals(JobState.RUNNING, state)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT last_checkin_at FROM job_records WHERE id = ?").use { stmt ->
                stmt.setLong(1, id)
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(checkInAt, rs.getLong("last_checkin_at"))
                }
            }
        }
    }

    @Test
    fun `updateLastCheckIn returns null for nonexistent ID`() {
        assertNull(db {  table.updateLastCheckIn(99999L, System.currentTimeMillis()) })
    }

    @Test
    fun `updateLastCheckIn is committed before the enclosing transaction ends`() {
        val id = insertWaitingJob()
        db { table.tryCaptureRunLock(id, "test-system", defaultSpec) }
        val checkInAt = System.currentTimeMillis()

        db {
            // Written in the enclosing transaction, so it must stay invisible to other connections.
            val uncommittedId = table.insertJob(defaultSpec)

            assertEquals(JobState.RUNNING, table.updateLastCheckIn(id, checkInAt))

            PostgresTestSupport.getConnection(postgres).use { conn ->
                conn.prepareStatement("SELECT last_checkin_at FROM job_records WHERE id = ?").use { stmt ->
                    stmt.setLong(1, id)
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals(checkInAt, rs.getLong("last_checkin_at"))
                    }
                }

                conn.prepareStatement("SELECT count(*) FROM job_records WHERE id = ?").use { stmt ->
                    stmt.setLong(1, uncommittedId)
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals(0, rs.getInt(1), "The enclosing transaction had already committed")
                    }
                }
            }
        }
    }
}
