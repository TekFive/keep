package org.tekfive.keep.job.db

import org.junit.jupiter.api.AfterAll
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.toJsonObject
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import org.tekfive.keep.db.DbConnection
import org.tekfive.keep.job.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
class JobRecordsTableIntegrationTest {

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

    private fun createSpec(
        typeId: String = "test-job",
        priority: Int? = null,
    ): JobSpec = object : JobSpec {
        override val estimateRuntime = false
        override val jobTypeIdentifier = typeId
        override val jobPriority = priority
        override val maxRetriesOnFailure: Int = 0
        override val minSecondsBetweenRetries: Int? = null
        override val retryExceptionBaseTypes: List<KClass<out Exception>> = emptyList()
        override fun getEstimatedRuntimeQueries(jobDetails: JsonObject): List<QueryNode> = emptyList()
        override fun createJob(): Job = throw UnsupportedOperationException()
    }

    @Test
    fun `insertJob with JSONB details round-trips correctly`() {
        val details = mapOf("key" to "value", "count" to 42).toJsonObject()

        PostgresTestSupport.getConnection(postgres).use { conn ->
            val insertSql = """
                INSERT INTO ${JobRecordsTable.tableName} (type, created_at, state, priority, attempt, job_details)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
            """.trimIndent()
            conn.prepareStatement(insertSql).use { stmt ->
                stmt.setString(1, "test-job")
                stmt.setLong(2, 1000L)
                stmt.setInt(3, JobState.PENDING.id)
                stmt.setInt(4, 0)
                stmt.setInt(5, 1)
                stmt.setString(6, details.toJsonString())
                stmt.executeUpdate()
            }

            conn.prepareStatement("SELECT job_details FROM ${JobRecordsTable.tableName} WHERE type = ?").use { stmt ->
                stmt.setString(1, "test-job")
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val json = rs.getString("job_details")
                    assertNotNull(json)
                    assertTrue(json.contains("\"key\""))
                    assertTrue(json.contains("\"value\""))
                }
            }
        }
    }

    @Test
    fun `getAverageRuntimeSecs calculates correct average`() {
        PostgresTestSupport.getConnection(postgres).use { conn ->
            val insertSql = """
                INSERT INTO ${JobRecordsTable.tableName} (type, created_at, state, priority, attempt, started_at, ended_at)
                VALUES (?, ?, ?, 0, 1, ?, ?)
            """.trimIndent()
            conn.prepareStatement(insertSql).use { stmt ->
                // Job 1: 10 seconds (10000 ms)
                stmt.setString(1, "avg-test")
                stmt.setLong(2, 1000L)
                stmt.setInt(3, JobState.COMPLETED.id)
                stmt.setLong(4, 1000L)
                stmt.setLong(5, 11000L)
                stmt.executeUpdate()

                // Job 2: 20 seconds (20000 ms)
                stmt.setString(1, "avg-test")
                stmt.setLong(2, 2000L)
                stmt.setInt(3, JobState.COMPLETED.id)
                stmt.setLong(4, 2000L)
                stmt.setLong(5, 22000L)
                stmt.executeUpdate()
            }
        }

        val avg = JobRecordsTable.getAverageRuntimeSecs("avg-test", 10, null)
        assertEquals(15, avg) // (10 + 20) / 2 = 15
    }

    @Test
    fun `getAverageRuntimeSecs respects maxRecords limit`() {
        PostgresTestSupport.getConnection(postgres).use { conn ->
            val insertSql = """
                INSERT INTO ${JobRecordsTable.tableName} (type, created_at, state, priority, attempt, started_at, ended_at)
                VALUES (?, ?, ?, 0, 1, ?, ?)
            """.trimIndent()
            conn.prepareStatement(insertSql).use { stmt ->
                // Oldest completed job: 100 seconds
                stmt.setString(1, "limit-test")
                stmt.setLong(2, 1000L)
                stmt.setInt(3, JobState.COMPLETED.id)
                stmt.setLong(4, 1000L)
                stmt.setLong(5, 101000L)
                stmt.executeUpdate()

                // Middle job: 10 seconds
                stmt.setString(1, "limit-test")
                stmt.setLong(2, 200000L)
                stmt.setInt(3, JobState.COMPLETED.id)
                stmt.setLong(4, 202000L)
                stmt.setLong(5, 212000L)
                stmt.executeUpdate()

                // Newest job: 20 seconds
                stmt.setString(1, "limit-test")
                stmt.setLong(2, 300000L)
                stmt.setInt(3, JobState.COMPLETED.id)
                stmt.setLong(4, 303000L)
                stmt.setLong(5, 323000L)
                stmt.executeUpdate()
            }
        }

        // Only take the 2 most recent by ended_at DESC: 323000 (20s) and 212000 (10s)
        val avg = JobRecordsTable.getAverageRuntimeSecs("limit-test", 2, null)
        assertEquals(15, avg) // (20 + 10) / 2 = 15
    }

    @Test
    fun `getAverageRuntimeSecs filters by parameterized JSON path`() {
        PostgresTestSupport.getConnection(postgres).use { conn ->
            val insertSql = """
                INSERT INTO ${JobRecordsTable.tableName} (type, created_at, state, priority, attempt, started_at, ended_at, job_details)
                VALUES (?, ?, ?, 0, 1, ?, ?, ?::jsonb)
            """.trimIndent()
            conn.prepareStatement(insertSql).use { stmt ->
                stmt.setString(1, "json-filter-test")
                stmt.setLong(2, 1000L)
                stmt.setInt(3, JobState.COMPLETED.id)
                stmt.setLong(4, 1000L)
                stmt.setLong(5, 11000L)
                stmt.setString(6, """{"tenant's":{"status":"active"}}""")
                stmt.executeUpdate()

                stmt.setString(1, "json-filter-test")
                stmt.setLong(2, 2000L)
                stmt.setInt(3, JobState.COMPLETED.id)
                stmt.setLong(4, 2000L)
                stmt.setLong(5, 102000L)
                stmt.setString(6, """{"tenant's":{"status":"inactive"}}""")
                stmt.executeUpdate()
            }
        }

        val avg = JobRecordsTable.getAverageRuntimeSecs(
            "json-filter-test",
            10,
            QueryNode.Condition(JsonPathOperator.Equals(listOf("tenant's", "status"), "active"))
        )
        assertEquals(10, avg)
    }

    @Test
    fun `getAverageRuntimeSecs filters by job type`() {
        PostgresTestSupport.getConnection(postgres).use { conn ->
            val insertSql = """
                INSERT INTO ${JobRecordsTable.tableName} (type, created_at, state, priority, attempt, started_at, ended_at)
                VALUES (?, ?, ?, 0, 1, ?, ?)
            """.trimIndent()
            conn.prepareStatement(insertSql).use { stmt ->
                // Type A: 10 seconds
                stmt.setString(1, "type-a")
                stmt.setLong(2, 1000L)
                stmt.setInt(3, JobState.COMPLETED.id)
                stmt.setLong(4, 1000L)
                stmt.setLong(5, 11000L)
                stmt.executeUpdate()

                // Type B: 100 seconds
                stmt.setString(1, "type-b")
                stmt.setLong(2, 2000L)
                stmt.setInt(3, JobState.COMPLETED.id)
                stmt.setLong(4, 2000L)
                stmt.setLong(5, 102000L)
                stmt.executeUpdate()
            }
        }

        val avgA = JobRecordsTable.getAverageRuntimeSecs("type-a", 10, null)
        assertEquals(10, avgA)

        val avgB = JobRecordsTable.getAverageRuntimeSecs("type-b", 10, null)
        assertEquals(100, avgB)
    }
}
