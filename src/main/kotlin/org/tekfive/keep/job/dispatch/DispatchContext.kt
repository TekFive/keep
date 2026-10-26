package org.tekfive.keep.job.dispatch

import org.tekfive.jfk.JsonObject
import org.tekfive.keep.job.db.JobRecord
import org.tekfive.keep.job.db.JobRecordLogLevel
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobLogger
import org.tekfive.keep.job.JobSpec
import org.tekfive.keep.job.JobState
import org.tekfive.keep.job.db.JobRecordsTable
import org.tekfive.keep.db.db
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobSubject
import org.tekfive.keep.job.JobTrigger
import java.util.UUID
import java.sql.SQLException
import java.util.concurrent.TimeUnit

internal class DispatchContext(
    val minSecondsBetweenCheckIn: Int,
    val job: Job,
    val jobSpec: JobSpec,
    val jobRecord: JobRecord,
    val jobsTable: JobRecordsTable,
    minSaveLogLevel: JobRecordLogLevel?,
    override val startedAt: Long = System.currentTimeMillis(),
    timeoutSeconds: Int = jobRecord.timeoutSeconds ?: jobSpec.timeoutSeconds ?: 0,
) : JobContext {

    override val jobId: Long = jobRecord.id
    private val executionToken: UUID
        get() = checkNotNull(jobRecord.executionToken) { "The job must be captured before it can report progress." }
    override val trigger: JobTrigger = jobRecord.trigger
    override val subject: JobSubject? = jobRecord.subject
    override val runId: UUID = jobRecord.runId
    override val tenantId: String? = jobRecord.tenantId
    override val applicationVersion: String? = jobRecord.applicationVersion
    override val processedCount: Long? get() = jobRecord.processedCount
    override val failedCount: Long? get() = jobRecord.failedCount
    override val totalCount: Long? get() = jobRecord.totalCount
    override val type: String = jobRecord.type
    val priority: Int = jobRecord.priority
    override val createdAt: Long = jobRecord.createdAt
    override val attempt: Int = jobRecord.attempt
    override val maxRetries: Int = jobSpec.maxRetriesOnFailure
    override val estimatedRuntimeSeconds: Int? = jobRecord.estimatedRuntimeSeconds
    override val details: JsonObject?
        get() = jobsTable.getJobDetails(jobId)
    override val log: JobLogger = JobLogger(job, this, minSaveLogLevel)


    // Persist check-ins before short per-job deadlines, including subsecond intervals.
    private val checkInMillis = if (timeoutSeconds > 0) {
        minOf(TimeUnit.SECONDS.toMillis(minSecondsBetweenCheckIn.toLong()), TimeUnit.SECONDS.toMillis(timeoutSeconds.toLong()) / 2)
    } else {
        TimeUnit.SECONDS.toMillis(minSecondsBetweenCheckIn.toLong())
    }

    var lastCheckInAt = startedAt
        private set

    override fun updateDetails(details: JsonObject) {
        db {
            if (!jobsTable.updateJobDetails(jobId, details, executionToken)) throwOwnershipLost()
        }
    }

    @Synchronized
    override fun updateProgress(processedCount: Long?, failedCount: Long?, totalCount: Long?) {
        db {
            if (!jobsTable.updateProgress(jobId, processedCount, failedCount, totalCount, executionToken)) throwOwnershipLost()
        }
        jobRecord.processedCount = processedCount
        jobRecord.failedCount = failedCount
        jobRecord.totalCount = totalCount
    }

    @Synchronized
    override fun checkIn(now: Long) {
        if (now - lastCheckInAt >= checkInMillis) {
            val jobState = try {
                jobsTable.updateLastCheckIn(jobId, now, executionToken)
            } catch (e: SQLException) {
                throw OnCheckInSqlException(e)
            }
            if (jobState == null) {
                throw JobNotFoundException(jobRecord.id)
            } else if (jobState == JobState.CANCEL_REQUESTED) {
                throw CancellationRequestedException(jobId)
            } else if (jobState.terminated) {
                throw TerminatedStateException(jobState)
            } else if (jobState != JobState.RUNNING) {
                throw JobOwnershipLostException(jobId)
            }
            lastCheckInAt = now
        }
    }

    private fun throwOwnershipLost(): Nothing {
        val current = jobsTable.findById(jobId) ?: throw JobNotFoundException(jobId)
        if (current.state.terminated) throw TerminatedStateException(current.state)
        throw JobOwnershipLostException(jobId)
    }
}
