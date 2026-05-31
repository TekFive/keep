package org.tekfive.keep.job.dispatch

import org.tekfive.jfk.JsonObject
import org.tekfive.keep.job.db.JobRecord
import org.tekfive.keep.job.db.JobRecordLogLevel
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobLogger
import org.tekfive.keep.job.JobSpec
import org.tekfive.keep.job.db.JobRecordsTable
import org.tekfive.keep.db.db
import org.tekfive.keep.job.Job
import java.sql.Connection
import java.sql.SQLException
import kotlin.math.roundToInt

internal class DispatchContext(
    val minSecondsBetweenCheckIn: Int,
    val job: Job,
    val jobSpec: JobSpec,
    val jobRecord: JobRecord,
    val jobsTable: JobRecordsTable,
    minSaveLogLevel: JobRecordLogLevel?,
    override val startedAt: Long = System.currentTimeMillis(),
) : JobContext {

    override val jobId: Long = jobRecord.id
    override val type: String = jobRecord.type
    val priority: Int = jobRecord.priority
    override val createdAt: Long = jobRecord.createdAt
    override val attempt: Int = jobRecord.attempt
    override val maxRetries: Int = jobSpec.maxRetriesOnFailure
    override val estimatedRuntimeSeconds: Int? = jobRecord.estimatedRuntimeSeconds
    override val details: JsonObject? = jobRecord.jobDetails
    override val log: JobLogger = JobLogger(job, this, minSaveLogLevel)


    var lastCheckInAt = startedAt
        private set

    override fun updateDetails(details: JsonObject) {
        db { jobsTable.updateJobDetails(jobRecord.id, details) }
    }

    @Synchronized
    override fun checkIn(now: Long) {
        val secondsSinceLastCheckIn = ((now - lastCheckInAt) / 1000.0).roundToInt()
        if (secondsSinceLastCheckIn >= minSecondsBetweenCheckIn) {
            lastCheckInAt = now
            val jobState = try {
                db { jobsTable.updateLastCheckIn(jobRecord.id, lastCheckInAt) }
            } catch (e: SQLException) {
                throw OnCheckInSqlException(e)
            }
            if (jobState == null) {
                throw JobNotFoundException(jobRecord.id)
            } else if (jobState.terminated) {
                throw TerminatedStateException(jobState)
            }
        }
    }
}
