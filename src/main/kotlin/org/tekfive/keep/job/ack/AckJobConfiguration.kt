package org.tekfive.keep.job.ack

import org.tekfive.ack.Ack
import org.tekfive.keep.job.BaseJobConfiguration
import org.tekfive.keep.job.JobConfiguration
import org.tekfive.keep.job.db.DatabaseRetryPolicy
import org.tekfive.keep.job.db.JobRecordLogLevel
import java.sql.SQLException
import java.time.InstantSource

abstract class BaseAckJobConfiguration() : JobConfiguration {

    val databaseRetryPolicy = DatabaseRetryPolicy(AckDatabaseRetrySettings)

    override val dispatchCount: Int
        get() = dispatchCountProperty()

    override val pollSeconds: Int
        get() = pollSecondsProperty()

    override val maximumCandidatesBuffer: Int
        get() = maximumCandidatesBufferProperty()

    override val maxEstimatedRuntimeRecords: Int
        get() = maxEstimatedRuntimeRecordsProperty()

    override val minSecondsBetweenJobCheckin: Int
        get() = minSecondsBetweenJobCheckinProperty()

    override val defaultJobTimeoutSeconds: Int
        get() = defaultJobTimeoutSecondsProperty()

    override val defaultMinSecondsBetweenJobRetry: Int
        get() = defaultMinSecondsBetweenJobRetryProperty()

    override val minSaveLogLevel: JobRecordLogLevel?
        get() = minSaveLogLevelProperty.orNull()?.uppercase()?.let { name ->
            JobRecordLogLevel.entries.find { it.name == name }
        }

    override fun getDatabaseBackoffSeconds(e: SQLException): Int {
        return (databaseRetryPolicy.getWaitTimeAndRecordError() / 1000).toInt()
    }

    companion object {
        val dispatchCountProperty = Ack.int("JOB_DISPATCH_COUNT", BaseJobConfiguration.DEFAULT_DISPATCH_COUNT, description = "Number of jobs dispatched per poll cycle.")
        val pollSecondsProperty = Ack.int("JOB_POLL_SECONDS", BaseJobConfiguration.DEFAULT_POLL_SECONDS, description = "Seconds between job queue polls.")
        val maximumCandidatesBufferProperty = Ack.int("JOB_MAXIMUM_CANDIDATES_BUFFER", default = { dispatchCountProperty() * 2 }, description = "Maximum number of candidate jobs buffered for dispatch.")
        val maxEstimatedRuntimeRecordsProperty = Ack.int("JOB_MAX_ESTIMATED_RUNTIME_RECORDS", BaseJobConfiguration.DEFAULT_MAX_ESTIMATED_RUNTIME_RECORDS, description = "Number of recent runs used to estimate a job's runtime.")
        val minSecondsBetweenJobCheckinProperty = Ack.int("JOB_MIN_SECONDS_BETWEEN_JOB_CHECKIN", BaseJobConfiguration.DEFAULT_MIN_SECONDS_BETWEEN_JOB_CHECKIN, description = "Minimum seconds between job check-ins while running.")
        val defaultJobTimeoutSecondsProperty = Ack.int("JOB_DEFAULT_TIMEOUT_SECONDS", BaseJobConfiguration.DEFAULT_JOB_TIMEOUT_SECONDS, description = "Default job execution timeout in seconds.")
        val defaultMinSecondsBetweenJobRetryProperty = Ack.int("JOB_DEFAULT_MIN_SECONDS_BETWEEN_JOB_RETRY", BaseJobConfiguration.DEFAULT_MIN_SECONDS_BETWEEN_JOB_RETRY, description = "Default minimum seconds between job retries.")
        val minSaveLogLevelProperty = Ack.string("JOB_MIN_SAVE_LOG_LEVEL", description = "Minimum log level persisted for job execution logs.")
    }
}

object AckJobConfiguration : BaseAckJobConfiguration()
