package org.tekfive.keep.job.db

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.db.dbConnection
import org.tekfive.keep.job.JobTrigger
import org.tekfive.keep.job.JobState
import org.tekfive.keep.schema.AppSchema
import org.tekfive.keep.schema.PostgresFreshInstallGenerator
import org.tekfive.keep.migration.dynamic.PostgresMigrationGenerator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val JOB_SCHEMA = "keep_job_install_test"

private object JobInstallSchema : AppSchema(JOB_SCHEMA) {
    override val tables = listOf(JobRecordsTable, JobHeartbeatsTable, JobRecordLogsTable, JobStateTransitionsTable)
    override val sequences = listOf("globalid")
}

/** Verifies the job tables, their custom indices, and the log foreign key install cleanly. */
class JobSchemaInstallTest {

    private lateinit var database: Database

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) { exec("DROP SCHEMA IF EXISTS $JOB_SCHEMA CASCADE") }
    }

    @AfterTest
    fun teardown() {
        transaction(database) { exec("DROP SCHEMA IF EXISTS $JOB_SCHEMA CASCADE") }
    }

    @Test
    fun `generated job schema DDL installs with the poll and foreign key indexes`() {
        val install = PostgresFreshInstallGenerator.plan(JobInstallSchema)

        transaction(database) {
            install.statements.forEach { exec(it) }
        }

        val indexNames = transaction(database) { indexNames() }

        assertTrue(indexNames.containsAll(
            setOf(
                "job_records_running_type_lock_key_uq",
                "job_records_running_concurrency_scope_idx",
                "job_records_scheduled_chain_type_uq",
                "job_records_pending_priority_created_idx",
                "job_records_type_state_idx",
                "job_records_run_id_idx",
                "job_records_retry_pending_idx",
                "job_record_logs_job_record_id_ix",
                "job_state_transitions_job_record_id_ix",
            )
        ), "Missing indexes, found: $indexNames")

        val foreignKeys = transaction(database) { foreignKeyNames() }
        assertTrue(
            foreignKeys.contains("job_record_logs_job_record_id_fk"),
            "Missing job record log foreign key, found: $foreignKeys",
        )
        transaction(database) {
            exec("SET LOCAL search_path TO $JOB_SCHEMA, public")
            exec("INSERT INTO job_records (id, type, created_at, priority, attempt, state) VALUES (1000, 'history', 1, 0, 1, 1)")
            assertEquals(1, JobStateTransitionsTable.getForJob(1000).size)
            assertTrue(JobRecordsTable.requestCancellation(1000, "operator"))
            val cancellation = JobStateTransitionsTable.getForJob(1000).last()
            assertEquals(JobState.CANCELLED, cancellation.toState)
            assertEquals("operator", cancellation.cancelRequestedByUserId)
            assertNotNull(cancellation.cancelRequestedAt)
            val plan = PostgresMigrationGenerator.plan(JobInstallSchema, nonDestructive = true)
            assertTrue(plan.isEmpty, "Job indexes must be preserved by dynamic migrations: $plan")
        }
    }

    @Test
    fun `reporting migration preserves triggers and groups surviving retry chains`() {
        transaction(database) {
            exec("CREATE SCHEMA $JOB_SCHEMA")
            exec("SET LOCAL search_path TO $JOB_SCHEMA, public")
            dbConnection().createStatement().use { statement ->
                statement.execute(javaClass.getResource("/jobs-legacy.sql")!!.readText())
                statement.execute("""
                    INSERT INTO job_records (id, type, created_at, priority, attempt, state, parent_job_id, scheduled_job, user_id, failure_details)
                    VALUES
                        (1, 'import', 1, 0, 1, 4, NULL, FALSE, 'user-a', 'Temporary failure'),
                        (2, 'import', 2, 0, 2, 4, 1, FALSE, 'user-a', NULL),
                        (3, 'import', 3, 0, 3, 3, 2, FALSE, 'user-a', NULL),
                        (4, 'scheduled', 4, 0, 1, 3, NULL, TRUE, 'schedule-owner', NULL),
                        (5, 'system', 5, 0, 1, 1, NULL, FALSE, NULL, NULL),
                        (6, 'orphan', 6, 0, 4, 4, 999, FALSE, NULL, NULL),
                        (7, 'orphan', 7, 0, 5, 3, 6, FALSE, NULL, NULL),
                        (8, 'different-type', 8, 0, 2, 3, 1, FALSE, NULL, NULL),
                        (9, 'cycle', 9, 0, 1, 3, 10, FALSE, NULL, NULL),
                        (10, 'cycle', 10, 0, 1, 3, 9, FALSE, NULL, NULL),
                        (11, 'running', 11, 0, 1, 2, NULL, FALSE, NULL, NULL)
                """.trimIndent())
                statement.execute("UPDATE job_records SET started_at = 90, last_checkin_at = 100 WHERE id = 11")
                statement.execute(javaClass.getResource("/migrations/job-reporting.sql")!!.readText())
                statement.execute(javaClass.getResource("/migrations/job-state-transitions.sql")!!.readText())
                statement.execute(javaClass.getResource("/migrations/job-execution-safety.sql")!!.readText())
                statement.execute(javaClass.getResource("/migrations/job-cancellation.sql")!!.readText())
                statement.execute(javaClass.getResource("/migrations/job-result.sql")!!.readText())
            }

            val migratedRunning = JobRecordsTable.getById(11)
            assertNotNull(migratedRunning.executionToken)
            assertEquals(100L, JobRecordsTable.getRunningJobs(listOf("running")).single().lastCheckInAt)
            exec("SELECT count(*) FROM job_heartbeats WHERE job_record_id = 11 AND last_checkin_at = 100") { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt(1))
            }
            val original = JobRecordsTable.getById(1)
            assertEquals(JobTrigger.user("user-a"), original.trigger)
            assertEquals("Temporary failure", original.resultSummary)
            assertEquals(original.runId, JobRecordsTable.getById(2).runId)
            assertEquals(original.runId, JobRecordsTable.getById(3).runId)
            assertEquals(JobTrigger.scheduled("schedule-owner"), JobRecordsTable.getById(4).trigger)
            assertEquals(JobTrigger.system(), JobRecordsTable.getById(5).trigger)
            assertNotEquals(original.runId, JobRecordsTable.getById(5).runId)
            assertEquals(JobRecordsTable.getById(6).runId, JobRecordsTable.getById(7).runId)
            assertNotEquals(original.runId, JobRecordsTable.getById(8).runId)
            assertNotEquals(JobRecordsTable.getById(9).runId, JobRecordsTable.getById(10).runId)
            assertNull(original.subject)
            assertNull(original.tenantId)
            assertNull(original.applicationVersion)
            assertNull(original.timeoutReason)
            assertNull(original.processedCount)
            assertNull(original.failedCount)
            assertNull(original.totalCount)
            assertNull(original.cancelRequestedByUserId)
            assertNull(original.cancelRequestedAt)
            assertTrue(JobStateTransitionsTable.getForJob(1).isEmpty(), "Migration must not invent earlier transitions")
            exec("INSERT INTO job_records (id, type, created_at, priority, attempt, state) VALUES (1000, 'history', 1, 0, 1, 1)")
            exec("SET LOCAL search_path TO public")
            exec("UPDATE $JOB_SCHEMA.job_records SET state = 2 WHERE id = 1000")
            exec("SET LOCAL search_path TO $JOB_SCHEMA, public")
            assertEquals(listOf(1, 2), JobStateTransitionsTable.getForJob(1000).map { it.toState.id })
            assertTrue(JobRecordsTable.requestCancellation(1000, "operator"))
            assertEquals(JobState.CANCEL_REQUESTED, JobStateTransitionsTable.getForJob(1000).last().toState)
            exec("SET LOCAL search_path TO public")
            exec("UPDATE $JOB_SCHEMA.job_records SET state = 5 WHERE id = 1000")
            exec("SET LOCAL search_path TO $JOB_SCHEMA, public")
            val cancellation = JobStateTransitionsTable.getForJob(1000).last()
            assertEquals(JobState.CANCELLED, cancellation.toState)
            assertEquals("operator", cancellation.cancelRequestedByUserId)
            assertNotNull(cancellation.cancelRequestedAt)
            exec("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = '$JOB_SCHEMA' AND table_name = 'job_records' AND column_name IN ('user_id', 'scheduled_job')
            """.trimIndent()) { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt(1))
            }
            val remaining = PostgresMigrationGenerator.plan(JobInstallSchema, nonDestructive = true)
            // The legacy SQL has older ID defaults and constraints unrelated to reporting.
            val reportingObjects = Regex("\\b(cancel_requested_at|cancel_requested_by_user_id|job_records_running_type_lock_key_uq|job_records_running_concurrency_scope_idx|execution_token|retry_at|job_heartbeats|job_records_retry_pending_idx|trigger_source|trigger_user_id|subject_type|subject_id|result_summary|application_version|run_id|timeout_reason|tenant_id|processed_count|failed_count|total_count|job_records_run_id_idx|job_records_scheduled_chain_type_uq|record_job_state_transition|job_records_record_state_transition)\\b")
            assertTrue(remaining.sqlStatements.none { reportingObjects.containsMatchIn(it) },
                "Reporting columns and indexes must match the declared schema: $remaining")
        }
    }

    private fun indexNames(): Set<String> {
        val names = mutableSetOf<String>()
        TransactionManager.current().exec(
            "SELECT indexname FROM pg_indexes WHERE schemaname = '$JOB_SCHEMA'"
        ) { rs ->
            while (rs.next()) {
                names += rs.getString("indexname")
            }
        }
        return names
    }

    private fun foreignKeyNames(): Set<String> {
        val names = mutableSetOf<String>()
        TransactionManager.current().exec(
            """
            SELECT c.conname
            FROM pg_constraint c
            JOIN pg_namespace n ON n.oid = c.connamespace
            WHERE n.nspname = '$JOB_SCHEMA' AND c.contype = 'f'
            """.trimIndent()
        ) { rs ->
            while (rs.next()) {
                names += rs.getString("conname")
            }
        }
        return names
    }
}
