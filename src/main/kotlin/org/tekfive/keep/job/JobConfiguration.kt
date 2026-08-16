package org.tekfive.keep.job

import org.tekfive.keep.job.db.DatabaseRetryPolicy
import org.tekfive.keep.job.db.JobRecordLogLevel
import java.sql.SQLException
import java.time.InstantSource

interface JobConfiguration {
    val dispatchCount: Int

    val pollSeconds: Int

    val maximumCandidatesBuffer: Int

    val maxEstimatedRuntimeRecords: Int

    val minSecondsBetweenJobCheckin: Int

    val defaultJobTimeoutSeconds: Int
        get() = BaseJobConfiguration.DEFAULT_JOB_TIMEOUT_SECONDS

    val defaultMinSecondsBetweenJobRetry: Int

    val minSaveLogLevel: JobRecordLogLevel?

    fun getDatabaseBackoffSeconds(e: SQLException): Int
}

abstract class BaseJobConfiguration() : JobConfiguration {

    val databaseRetryPolicy = DatabaseRetryPolicy()

    override val dispatchCount: Int = DEFAULT_DISPATCH_COUNT

    override val pollSeconds: Int = DEFAULT_POLL_SECONDS

    override val maximumCandidatesBuffer: Int = dispatchCount * 2

    override val maxEstimatedRuntimeRecords: Int = DEFAULT_MAX_ESTIMATED_RUNTIME_RECORDS

    override val minSecondsBetweenJobCheckin: Int = DEFAULT_MIN_SECONDS_BETWEEN_JOB_CHECKIN

    override val defaultJobTimeoutSeconds: Int = DEFAULT_JOB_TIMEOUT_SECONDS

    override val defaultMinSecondsBetweenJobRetry: Int = DEFAULT_MIN_SECONDS_BETWEEN_JOB_RETRY

    override val minSaveLogLevel: JobRecordLogLevel? = DEFAULT_MIN_SAVE_LOG_LEVEL

    override fun getDatabaseBackoffSeconds(e: SQLException): Int {
        return databaseRetryPolicy.getWaitTimeAndRecordError().toInt()
    }

    companion object {
        val DEFAULT_DISPATCH_COUNT = Runtime.getRuntime().availableProcessors()
        const val DEFAULT_POLL_SECONDS: Int = 30
        const val DEFAULT_MAX_ESTIMATED_RUNTIME_RECORDS: Int = 10
        const val DEFAULT_MIN_SECONDS_BETWEEN_JOB_CHECKIN: Int = 30
        const val DEFAULT_JOB_TIMEOUT_SECONDS: Int = 60 * 5
        const val DEFAULT_MIN_SECONDS_BETWEEN_JOB_RETRY: Int = 60 * 5
        val DEFAULT_MIN_SAVE_LOG_LEVEL: JobRecordLogLevel? = null
    }
}

open class DefaultJobConfiguration : BaseJobConfiguration()
