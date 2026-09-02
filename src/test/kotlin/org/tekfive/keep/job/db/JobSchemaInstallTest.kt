package org.tekfive.keep.job.db

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.schema.AppSchema
import org.tekfive.keep.schema.PostgresFreshInstallGenerator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val JOB_SCHEMA = "keep_job_install_test"

private object JobInstallSchema : AppSchema(JOB_SCHEMA) {
    override val tables = listOf(JobRecordsTable, JobRecordLogsTable)
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
                "job_record_logs_job_record_id_ix",
            )
        ), "Missing indexes, found: $indexNames")

        val foreignKeys = transaction(database) { foreignKeyNames() }
        assertTrue(
            foreignKeys.contains("job_record_logs_job_record_id_fk"),
            "Missing job record log foreign key, found: $foreignKeys",
        )
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
