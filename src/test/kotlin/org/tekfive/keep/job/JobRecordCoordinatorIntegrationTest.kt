package org.tekfive.keep.job

import org.junit.jupiter.api.AfterAll
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.toJsonObject
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import org.tekfive.keep.db.DbConnection
import org.tekfive.keep.db.db
import org.tekfive.keep.job.db.JobRecordsTable
import org.tekfive.keep.job.db.PostgresTestSupport
import org.tekfive.keep.job.db.QueryNode
import org.tekfive.keep.job.schedule.FixedIntervalJobSpec
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
class JobRecordCoordinatorIntegrationTest {

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

    private fun createConfig(
        pollSeconds: Int = 1,
        dispatchCount: Int = 2,
        maxEstimatedRuntimeRecords: Int = 0,
    ): JobConfiguration {
        return object : JobConfiguration {
            override val dispatchCount: Int = dispatchCount
            override val pollSeconds: Int = pollSeconds
            override val maximumCandidatesBuffer: Int = dispatchCount * 2
            override val maxEstimatedRuntimeRecords: Int = maxEstimatedRuntimeRecords
            override val minSecondsBetweenJobCheckin: Int = 30
            override val defaultMinSecondsBetweenJobRetry: Int = 0
            override val minSaveLogLevel: org.tekfive.keep.job.db.JobRecordLogLevel? = null
            override fun getDatabaseBackoffSeconds(e: SQLException): Int = 1
        }
    }

    private val jobsTable = JobRecordsTable

    private fun insertJob(spec: JobSpec, details: JsonObject? = null): Long {
        return db { jobsTable.insertJob(spec, details = details) }
    }

    private fun readJobRecord(jobId: Long): Map<String, Any?> {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT * FROM job_records WHERE id = ?").use { stmt ->
                stmt.setLong(1, jobId)
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next(), "Job $jobId not found")
                    val meta = rs.metaData
                    val result = mutableMapOf<String, Any?>()
                    for (i in 1..meta.columnCount) {
                        val value = rs.getObject(i)
                        result[meta.getColumnName(i)] = if (rs.wasNull()) null else value
                    }
                    return result
                }
            }
        }
    }

    private fun awaitJobState(jobId: Long, stateId: Int, timeoutMs: Long = 10_000): Map<String, Any?> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val record = readJobRecord(jobId)
            if (record["state"] == stateId) return record
            Thread.sleep(100)
        }
        val record = readJobRecord(jobId)
        assertEquals(stateId, record["state"], "Job $jobId did not reach state $stateId within ${timeoutMs}ms")
        return record
    }

    private fun createSpec(
        typeId: String = "test-job",
        priority: Int? = null,
        maxRetries: Int = 0,
        minSecsBetweenRetries: Int? = null,
        retryExceptionTypes: List<KClass<out Exception>> = emptyList(),
        jobFactory: () -> Job,
    ): JobSpec = object : JobSpec {
        override val estimateRuntime = false
        override val jobTypeIdentifier = typeId
        override val jobPriority = priority
        override val maxRetriesOnFailure: Int = maxRetries
        override val minSecondsBetweenRetries = minSecsBetweenRetries
        override val retryExceptionBaseTypes = retryExceptionTypes
        override fun getEstimatedRuntimeQueries(jobDetails: JsonObject): List<QueryNode> = emptyList()
        override fun createJob(): Job = jobFactory()
    }

    private fun createFixedIntervalScheduledSpec(
        typeId: String,
        intervalSeconds: Long,
        jobFactory: () -> Job,
    ): FixedIntervalJobSpec = object : FixedIntervalJobSpec {
        override val estimateRuntime = false
        override val jobTypeIdentifier = typeId
        override val jobPriority: Int? = null
        override val maxRetriesOnFailure: Int = 0
        override val minSecondsBetweenRetries: Int? = null
        override val retryExceptionBaseTypes: List<KClass<out Exception>> = emptyList()
        override val intervalSeconds: Long = intervalSeconds
        override fun getEstimatedRuntimeQueries(jobDetails: JsonObject): List<QueryNode> = emptyList()
        override fun createJob(): Job = jobFactory()
    }

    private fun insertCompletedScheduledJob(typeId: String, endedAt: Long, createdAt: Long = endedAt - 1_000L) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                    INSERT INTO job_records (
                        type, created_at, priority, attempt, state, scheduled_job, system_identifier,
                        started_at, ended_at
                    ) VALUES (?, ?, 0, 1, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, typeId)
                stmt.setLong(2, createdAt)
                stmt.setInt(3, JobState.COMPLETED.id)
                stmt.setBoolean(4, true)
                stmt.setString(5, "seed-system")
                stmt.setLong(6, endedAt)
                stmt.setLong(7, endedAt)
                stmt.executeUpdate()
            }
        }
    }

    private fun insertRunningJob(typeId: String, systemIdentifier: String, startedAt: Long): Long {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                    INSERT INTO job_records (
                        type, created_at, priority, attempt, state, scheduled_job, system_identifier, started_at
                    ) VALUES (?, ?, 0, 1, ?, FALSE, ?, ?)
                    RETURNING id
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, typeId)
                stmt.setLong(2, startedAt - 1_000L)
                stmt.setInt(3, JobState.RUNNING.id)
                stmt.setString(4, systemIdentifier)
                stmt.setLong(5, startedAt)
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    return rs.getLong(1)
                }
            }
        }
    }

    private fun countJobs(typeId: String): Int {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM job_records WHERE type = ?").use { stmt ->
                stmt.setString(1, typeId)
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    return rs.getInt(1)
                }
            }
        }
    }

    @Test
    fun `coordinator picks up and completes a job`() {
        val config = createConfig()
        val latch = CountDownLatch(1)

        val spec = createSpec(typeId = "simple-job") {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    latch.countDown()
                    return JobCompleted()
                }
            }
        }

        val registry = JobRegistry()
        registry.register(spec)

        val jobId = insertJob(spec)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Job was not executed within timeout")
            val record = awaitJobState(jobId, JobState.COMPLETED.id)
            assertNotNull(record["ended_at"])
            assertNotNull(record["started_at"])
            assertEquals("test-system", record["system_identifier"])
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `coordinator marks failed job with failure details`() {
        val config = createConfig()
        val latch = CountDownLatch(1)

        val spec = createSpec(typeId = "failing-job") {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    latch.countDown()
                    return JobFailed("Something broke")
                }
            }
        }

        val registry = JobRegistry()
        registry.register(spec)

        val jobId = insertJob(spec)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Job was not executed within timeout")
            val record = awaitJobState(jobId, JobState.FAILED.id)
            assertEquals("Something broke", record["failure_details"])
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `coordinator retries a job when retryIfAllowed is true and retries remain`() {
        val config = createConfig()
        val executionCount = AtomicInteger(0)
        val allDone = CountDownLatch(2)

        val spec = createSpec(typeId = "retry-job", maxRetries = 1) {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    val count = executionCount.incrementAndGet()
                    allDone.countDown()
                    return if (count == 1) {
                        JobFailed("Transient error", retryIfAllowed = true)
                    } else {
                        JobCompleted()
                    }
                }
            }
        }

        val registry = JobRegistry()
        registry.register(spec)

        val originalJobId = insertJob(spec)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(allDone.await(15, TimeUnit.SECONDS), "Both job executions did not complete within timeout")

            // Original job should be FAILED
            val originalRecord = awaitJobState(originalJobId, JobState.FAILED.id)
            assertEquals("Transient error", originalRecord["failure_details"])

            // Find the retry job
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement("SELECT * FROM job_records WHERE parent_job_id = ?").use { stmt ->
                    stmt.setLong(1, originalJobId)
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next(), "Retry job not found")
                        assertEquals(2, rs.getInt("attempt"))
                        awaitJobState(rs.getLong("id"), JobState.COMPLETED.id)
                    }
                }
            }
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `coordinator processes multiple jobs concurrently`() {
        val config = createConfig(dispatchCount = 3)
        val concurrentCount = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val allStarted = CountDownLatch(3)
        val gate = CountDownLatch(1)

        val spec = createSpec(typeId = "concurrent-job") {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    val current = concurrentCount.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    allStarted.countDown()
                    gate.await(10, TimeUnit.SECONDS)
                    concurrentCount.decrementAndGet()
                    return JobCompleted()
                }
            }
        }

        val registry = JobRegistry()
        registry.register(spec)

        val ids = (1..3).map { insertJob(spec) }

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(allStarted.await(15, TimeUnit.SECONDS), "Not all jobs started within timeout")
            assertTrue(maxConcurrent.get() >= 2, "Expected at least 2 concurrent executions but got ${maxConcurrent.get()}")
            gate.countDown()

            for (id in ids) {
                awaitJobState(id, JobState.COMPLETED.id)
            }
        } finally {
            gate.countDown() // ensure gate is released if assertion failed
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `coordinator respects job priority ordering`() {
        val config = createConfig(dispatchCount = 1)
        val executionOrder = CopyOnWriteArrayList<Int>()
        val allDone = CountDownLatch(3)

        fun specForPriority(priority: Int) = createSpec(
            typeId = "priority-job-$priority",
            priority = priority,
        ) {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    executionOrder.add(priority)
                    allDone.countDown()
                    return JobCompleted()
                }
            }
        }

        val lowSpec = specForPriority(1)
        val medSpec = specForPriority(5)
        val highSpec = specForPriority(10)

        val registry = JobRegistry()
        registry.register(lowSpec)
        registry.register(medSpec)
        registry.register(highSpec)

        insertJob(lowSpec)
        insertJob(medSpec)
        insertJob(highSpec)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(allDone.await(15, TimeUnit.SECONDS), "Not all jobs completed within timeout")
            assertEquals(10, executionOrder[0], "Highest priority job should run first")
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `coordinator passes job details to executing job`() {
        val config = createConfig()
        val latch = CountDownLatch(1)
        var receivedDetails: JsonObject? = null

        val spec = createSpec(typeId = "details-job") {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    receivedDetails = context.details
                    latch.countDown()
                    return JobCompleted()
                }
            }
        }

        val registry = JobRegistry()
        registry.register(spec)

        val details = mapOf("key" to "value", "count" to 42).toJsonObject()
        val jobId = insertJob(spec, details = details)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Job was not executed within timeout")
            assertNotNull(receivedDetails)
            assertEquals("value", receivedDetails!!["key"].string)
            assertEquals(42, receivedDetails!!["count"].int)
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `coordinator handles job that throws exception`() {
        val config = createConfig()
        val latch = CountDownLatch(1)

        val spec = createSpec(typeId = "exception-job") {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    latch.countDown()
                    throw RuntimeException("Unexpected error")
                }
            }
        }

        val registry = JobRegistry()
        registry.register(spec)

        val jobId = insertJob(spec)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Job was not executed within timeout")
            val record = awaitJobState(jobId, JobState.FAILED.id)
            val failureDetails = record["failure_details"] as? String
            assertNotNull(failureDetails)
            assertTrue(failureDetails.contains("Unexpected error"))
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `coordinator stop interrupts and waits for dispatchers`() {
        val config = createConfig()
        val jobStarted = CountDownLatch(1)

        val spec = createSpec(typeId = "long-job") {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    jobStarted.countDown()
                    try {
                        Thread.sleep(60_000)
                    } catch (_: InterruptedException) {}
                    return JobCompleted()
                }
            }
        }

        val registry = JobRegistry()
        registry.register(spec)

        insertJob(spec)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        assertTrue(jobStarted.await(15, TimeUnit.SECONDS), "Job did not start within timeout")

        val stopStart = System.currentTimeMillis()
        coordinator.stop(waitForStop = true)
        val stopDuration = System.currentTimeMillis() - stopStart

        // stop should complete quickly (within a few seconds) since it interrupts threads
        assertTrue(stopDuration < 10_000, "stop(waitForStop=true) took too long: ${stopDuration}ms")
    }

    @Test
    fun `coordinator does not retry when retries exhausted`() {
        val config = createConfig()
        val executionCount = AtomicInteger(0)
        val latch = CountDownLatch(1)

        val spec = createSpec(typeId = "no-retry-job", maxRetries = 0) {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    executionCount.incrementAndGet()
                    latch.countDown()
                    return JobFailed("Error", retryIfAllowed = true)
                }
            }
        }

        val registry = JobRegistry()
        registry.register(spec)

        val jobId = insertJob(spec)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Job was not executed within timeout")
            awaitJobState(jobId, JobState.FAILED.id)

            // Wait a bit to confirm no retry job was created
            Thread.sleep(2000)

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM job_records WHERE parent_job_id = ?").use { stmt ->
                    stmt.setLong(1, jobId)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(0, rs.getInt(1), "No retry job should have been created")
                    }
                }
            }
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `coordinator retries exception-throwing job when exception type matches`() {
        val config = createConfig()
        val executionCount = AtomicInteger(0)
        val allDone = CountDownLatch(2)

        val spec = createSpec(
            typeId = "retry-exception-job",
            maxRetries = 1,
            retryExceptionTypes = listOf(IllegalStateException::class),
        ) {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    val count = executionCount.incrementAndGet()
                    allDone.countDown()
                    if (count == 1) {
                        throw IllegalStateException("Transient failure")
                    }
                    return JobCompleted()
                }
            }
        }

        val registry = JobRegistry()
        registry.register(spec)

        val originalJobId = insertJob(spec)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(allDone.await(15, TimeUnit.SECONDS), "Both executions did not complete within timeout")

            // Original should be FAILED
            awaitJobState(originalJobId, JobState.FAILED.id)

            // Retry job should exist and succeed
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement("SELECT id FROM job_records WHERE parent_job_id = ?").use { stmt ->
                    stmt.setLong(1, originalJobId)
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next(), "Retry job not found")
                        awaitJobState(rs.getLong(1), JobState.COMPLETED.id)
                    }
                }
            }
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `scheduled fixed interval job reschedules from latest failed run instead of rerunning immediately`() {
        val config = createConfig(pollSeconds = 1, dispatchCount = 1)
        val executionCount = AtomicInteger(0)
        val firstRun = CountDownLatch(1)
        val typeId = "scheduled-fixed-failure"
        val intervalSeconds = 60L

        val spec = createFixedIntervalScheduledSpec(typeId, intervalSeconds) {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    executionCount.incrementAndGet()
                    firstRun.countDown()
                    return JobFailed("boom")
                }
            }
        }

        val registry = JobRegistry()
        registry.register(spec)

        val now = System.currentTimeMillis()
        insertCompletedScheduledJob(typeId, now - (intervalSeconds * 1000L * 2))

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(firstRun.await(15, TimeUnit.SECONDS), "Scheduled job did not execute within timeout")

            Thread.sleep(2_500)

            assertEquals(1, executionCount.get(), "Scheduled job should not rerun immediately after failure")

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    """
                        SELECT state, minimum_start_at
                        FROM job_records
                        WHERE type = ?
                        ORDER BY created_at DESC
                        LIMIT 1
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, typeId)
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next(), "Expected a follow-up scheduled job")
                        assertEquals(JobState.PENDING.id, rs.getInt("state"))
                        val minimumStartAt = rs.getLong("minimum_start_at")
                        assertTrue(!rs.wasNull(), "Follow-up scheduled job should have a minimum_start_at")
                        assertTrue(
                            minimumStartAt > System.currentTimeMillis() + 30_000,
                            "Follow-up scheduled job should be delayed by the fixed interval after failure",
                        )
                    }
                }
            }
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `coordinator keeps dispatching when a scheduled spec throws while scheduling`() {
        val config = createConfig()
        val latch = CountDownLatch(1)

        val brokenScheduledSpec = object : FixedIntervalJobSpec {
            override val estimateRuntime = false
            override val jobTypeIdentifier = "broken-schedule"
            override val intervalSeconds: Long
                get() = throw IllegalStateException("interval misconfigured")
            override fun createJob(): Job = throw IllegalStateException("never constructed")
        }
        val spec = createSpec(typeId = "healthy-job") {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    latch.countDown()
                    return JobCompleted()
                }
            }
        }

        val registry = JobRegistry()
        registry.register(brokenScheduledSpec)
        registry.register(spec)

        val jobId = insertJob(spec)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Healthy job was not executed while a scheduled spec kept throwing")
            awaitJobState(jobId, JobState.COMPLETED.id)
            assertTrue(coordinator.isRunning, "Coordinator thread should survive a throwing scheduled spec")
            assertEquals(0, countJobs("broken-schedule"))
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `coordinator fails a job whose factory throws and keeps dispatching`() {
        val config = createConfig(dispatchCount = 1)
        val latch = CountDownLatch(1)

        val brokenSpec = createSpec(typeId = "broken-factory") {
            throw IllegalStateException("factory exploded")
        }
        val spec = createSpec(typeId = "healthy-job") {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    latch.countDown()
                    return JobCompleted()
                }
            }
        }

        val registry = JobRegistry()
        registry.register(brokenSpec)
        registry.register(spec)

        val brokenIds = listOf(insertJob(brokenSpec), insertJob(brokenSpec))
        val healthyId = insertJob(spec)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Healthy job was not executed after factory failures")
            awaitJobState(healthyId, JobState.COMPLETED.id)
            for (brokenId in brokenIds) {
                val record = awaitJobState(brokenId, JobState.FAILED.id)
                val details = record["failure_details"] as String
                assertTrue(details.contains("factory exploded"), "Unexpected failure details: $details")
            }
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `coordinator fails a job that throws an Error instead of leaving it running`() {
        val config = createConfig(dispatchCount = 1)
        val latch = CountDownLatch(1)

        val errorSpec = createSpec(typeId = "error-job") {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    throw AssertionError("assertion in job")
                }
            }
        }
        val spec = createSpec(typeId = "healthy-job") {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    latch.countDown()
                    return JobCompleted()
                }
            }
        }

        val registry = JobRegistry()
        registry.register(errorSpec)
        registry.register(spec)

        val errorId = insertJob(errorSpec)
        val healthyId = insertJob(spec)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Healthy job was not executed after an Error in another job")
            awaitJobState(healthyId, JobState.COMPLETED.id)
            val record = awaitJobState(errorId, JobState.FAILED.id)
            assertEquals("assertion in job", record["failure_details"])
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `coordinator returns an interrupted job to pending on stop`() {
        val config = createConfig(dispatchCount = 1)
        val jobStarted = CountDownLatch(1)

        val spec = createSpec(typeId = "interruptible-job") {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    jobStarted.countDown()
                    Thread.sleep(60_000)
                    return JobCompleted()
                }
            }
        }

        val registry = JobRegistry()
        registry.register(spec)

        val jobId = insertJob(spec)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        assertTrue(jobStarted.await(15, TimeUnit.SECONDS), "Job did not start within timeout")
        coordinator.stop(waitForStop = true)

        val record = readJobRecord(jobId)
        assertEquals(JobState.PENDING.id, record["state"])
        assertEquals(null, record["system_identifier"])
        assertEquals(null, record["started_at"])
        assertEquals(1, record["attempt"])
    }

    @Test
    fun `coordinator reclaims jobs orphaned by a previous run of the same system on start`() {
        val config = createConfig(dispatchCount = 1)
        val latch = CountDownLatch(1)

        val spec = createSpec(typeId = "orphaned-job") {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    latch.countDown()
                    return JobCompleted()
                }
            }
        }

        val registry = JobRegistry()
        registry.register(spec)

        val startedAt = System.currentTimeMillis() - 60_000L
        val orphanId = insertRunningJob("orphaned-job", "test-system", startedAt)
        val foreignId = insertRunningJob("orphaned-job", "other-system", startedAt)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Orphaned job was not re-run within timeout")
            val record = awaitJobState(orphanId, JobState.COMPLETED.id)
            assertEquals("test-system", record["system_identifier"])
            assertEquals(JobState.RUNNING.id, readJobRecord(foreignId)["state"], "Another system's running job must not be reclaimed")
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `wakeUp dispatches a new job without waiting for the poll interval`() {
        val config = createConfig(pollSeconds = 60, dispatchCount = 1)
        val latch = CountDownLatch(1)

        val spec = createSpec(typeId = "woken-job") {
            object : Job {
                override fun execute(context: JobContext): JobResult {
                    latch.countDown()
                    return JobCompleted()
                }
            }
        }

        val registry = JobRegistry()
        registry.register(spec)

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            // Let the initial poll pass so the coordinator is inside its 60 second wait.
            Thread.sleep(1_500)
            val jobId = insertJob(spec)
            coordinator.wakeUp()
            assertTrue(latch.await(5, TimeUnit.SECONDS), "wakeUp did not trigger a poll")
            awaitJobState(jobId, JobState.COMPLETED.id)
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `failure to schedule a retry does not leave the job running`() {
        val config = createConfig(dispatchCount = 1, maxEstimatedRuntimeRecords = 5)
        val latch = CountDownLatch(1)

        val spec = object : JobSpec {
            override val estimateRuntime = false
            override val jobTypeIdentifier = "broken-retry"
            override val maxRetriesOnFailure: Int = 2
            override val minSecondsBetweenRetries: Int? = 0
            override fun getEstimatedRuntimeQueries(jobDetails: JsonObject): List<QueryNode> = throw IllegalStateException("bad runtime query")
            override fun createJob(): Job = object : Job {
                override fun execute(context: JobContext): JobResult {
                    latch.countDown()
                    return JobFailed("boom", retryIfAllowed = true)
                }
            }
        }

        val registry = JobRegistry()
        registry.register(spec)

        val jobId = insertJob(spec, details = mapOf("k" to "v").toJsonObject())

        val coordinator = JobCoordinator("test-system", registry, config)
        coordinator.start()

        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Job was not executed within timeout")
            val record = awaitJobState(jobId, JobState.FAILED.id)
            assertEquals("boom", record["failure_details"])
            Thread.sleep(1_500)
            assertEquals(1, countJobs("broken-retry"), "No retry record can be created when retry preparation fails")
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }
}
