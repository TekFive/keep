package org.tekfive.keep.job.db

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.tekfive.ack.Ack
import org.tekfive.ack.ackNamespace
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.db.db
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobCompleted
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobResult
import org.tekfive.keep.job.JobState
import org.tekfive.keep.job.schedule.FixedIntervalJobSpec
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.days

/**
 * Periodically deletes terminated (completed) job records from [JobRecordsTable] along with their
 * associated log lines in [JobRecordLogsTable].
 *
 * Retention is split by outcome: [JobState.COMPLETED] (succeeded) records are kept for
 * [completedKeepDaysAck] days, while the other terminal states (failed, cancelled, timed out, etc.)
 * are kept for [failedKeepDaysAck] days, which defaults to the completed keep time when not
 * configured. Age is measured from [JobRecordsTable.endedAt] — when the job reached its terminal
 * state; records without an end time are never purged.
 *
 * [JobRecordLogsTable] references [JobRecordsTable] without an ON DELETE cascade, so the log rows
 * for the purged records are deleted first, then the records themselves.
 */
class JobRecordCleaner : Job {

    companion object : FixedIntervalJobSpec {
        override val estimateRuntime = false
        override val jobPriority: Int? = null
        override val maxRetriesOnFailure: Int = 0
        override val minSecondsBetweenRetries: Int? = null
        override val retryExceptionBaseTypes: List<KClass<out Exception>> = emptyList()

        override fun getEstimatedRuntimeQueries(jobDetails: JsonObject): List<QueryNode> = emptyList()

        override fun createJob(): Job = JobRecordCleaner()

        override val intervalSecondsProperty: Ack<Long>
            get() = Ack.long("FIXED_INTERVAL_SECONDS", 24L * 60 * 60, namespace = ackNamespace(getNamespaceClass()), description = "Interval in seconds between job-record cleanup runs.")

        val completedKeepDaysAck = Ack.int("COMPLETED_KEEP_DAYS", 5, min = 0,
            namespace = "JOB_RECORD_CLEANER",
            description = "Age in days after which successfully completed job records (and their logs) are deleted.")

        val failedKeepDaysAck = Ack.int("FAILED_KEEP_DAYS", min = 0,
            namespace = "JOB_RECORD_CLEANER",
            description = "Age in days after which failed job records (failed, cancelled, timed out, etc.) and their logs are deleted. Defaults to the completed keep time.") { completedKeepDaysAck().let { it + (it * 0.5).roundToInt() } }

        private val failedStates = JobState.terminatedStates.filter { it != JobState.COMPLETED }
    }

    override fun execute(context: JobContext): JobResult {
        val now = System.currentTimeMillis()
        val completedCutoffAt = now - completedKeepDaysAck().days.inWholeMilliseconds
        val failedCutoffAt = now - failedKeepDaysAck().days.inWholeMilliseconds

        db {
            val purgeIds = JobRecordsTable.select(JobRecordsTable.id).where {
                JobRecordsTable.endedAt.isNotNull() and (
                    ((JobRecordsTable.state eq JobState.COMPLETED) and (JobRecordsTable.endedAt lessEq completedCutoffAt)) or
                        ((JobRecordsTable.state inList failedStates) and (JobRecordsTable.endedAt lessEq failedCutoffAt))
                )
            }.map { it[JobRecordsTable.id] }

            if (purgeIds.isNotEmpty()) {
                // Logs reference job records without a cascade, so remove them before their records.
                val logsDeleted = JobRecordLogsTable.deleteWhere { JobRecordLogsTable.jobRecordId inList purgeIds }
                val recordsDeleted = JobRecordsTable.deleteWhere { JobRecordsTable.id inList purgeIds }
                context.log.info("Deleted $recordsDeleted terminated job records and $logsDeleted job record logs.")
            }
        }

        return JobCompleted()
    }
}
