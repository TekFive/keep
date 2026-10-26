package org.tekfive.keep.job.db

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.dataEnum
import org.tekfive.keep.data.fkey
import org.tekfive.keep.db.db
import org.tekfive.keep.job.JobState
import org.tekfive.keep.job.JobTimeoutReason
import org.tekfive.keep.schema.PostgresTriggerEvent
import org.tekfive.keep.schema.postgresObjects
import java.util.UUID

/**
 * Include alongside [JobRecordsTable] in the application schema to install state history.
 * The database records inserts and actual state changes, including direct SQL, in the same
 * transaction. Deleting a job cascades to its history; rolled-back changes leave no history.
 */
object JobStateTransitionsTable : DataTable<JobStateTransition>("job_state_transitions") {
    val jobRecordId = fkey("job_record_id", JobRecordsTable)
    val fromState = dataEnum<JobState>("from_state").nullable()
    val toState = dataEnum<JobState>("to_state")
    val transitionedAt = long("transitioned_at")
    val systemIdentifier = varchar("system_identifier", 255).nullable()
    val applicationVersion = text("application_version").nullable()
    val resultSummary = text("result_summary").nullable()
    val timeoutReason = dataEnum<JobTimeoutReason>("timeout_reason").nullable()
    val processedCount = long("processed_count").nullable()
    val failedCount = long("failed_count").nullable()
    val totalCount = long("total_count").nullable()
    val cancelRequestedByUserId = text("cancel_requested_by_user_id").nullable()
    val cancelRequestedAt = long("cancel_requested_at").nullable()

    override val postgresObjects = JobRecordsTable.postgresObjects {
        rowTrigger("job_records_record_state_transition", functionName = "record_job_state_transition") {
            after(PostgresTriggerEvent.INSERT, PostgresTriggerEvent.UPDATE)
            onInsert { sql(recordTransitionSql("NULL::integer", "NEW.system_identifier")) }
            onUpdate {
                sql("IF OLD.state IS DISTINCT FROM NEW.state THEN\n" +
                    recordTransitionSql("OLD.state", "COALESCE(NEW.system_identifier, OLD.system_identifier)").prependIndent("    ") +
                    "\nEND IF;")
            }
        }
    }

    fun getForJob(jobRecordId: Long): List<JobStateTransition> = db {
        selectAll().where { this@JobStateTransitionsTable.jobRecordId eq jobRecordId }
            .orderBy(transitionedAt to SortOrder.ASC, id to SortOrder.ASC).map(::map)
    }

    fun getForRun(runId: UUID): List<JobStateTransition> = db {
        innerJoin(JobRecordsTable).select(columns).where { JobRecordsTable.runId eq runId }
            .orderBy(transitionedAt to SortOrder.ASC, id to SortOrder.ASC).map(::map)
    }

    // Qualify both objects using the triggering table's schema, independent of the caller's search path.
    private fun recordTransitionSql(fromState: String, systemIdentifier: String): String = """
        EXECUTE format(
            'INSERT INTO %I.job_state_transitions (id, job_record_id, from_state, to_state, transitioned_at, system_identifier, application_version, result_summary, timeout_reason, processed_count, failed_count, total_count, cancel_requested_by_user_id, cancel_requested_at) VALUES (nextval(%L::regclass), $1, $2, $3, floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint, $4, $5, $6, $7, $8, $9, $10, $11, $12)',
            TG_TABLE_SCHEMA, format('%I.globalid', TG_TABLE_SCHEMA)
        ) USING NEW.id, ${fromState}, NEW.state, ${systemIdentifier}, NEW.application_version, NEW.result_summary, NEW.timeout_reason, NEW.processed_count, NEW.failed_count, NEW.total_count, NEW.cancel_requested_by_user_id, NEW.cancel_requested_at;
    """.trimIndent()
}
