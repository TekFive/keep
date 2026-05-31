package org.tekfive.keep.job.schedule

import org.tekfive.jfk.JsonObject
import org.tekfive.keep.job.JobSpec
import org.tekfive.keep.job.JobState
import org.tekfive.keep.job.db.JobRecord

interface ScheduledJobSpec : JobSpec {

    fun schedule(lastEndedAt: Long?): JobRecord?

    fun createScheduledJob(
        minimumStartAt: Long?,
        jobDetails: JsonObject? = null,
        lockKey: String? = null,
        maxConcurrentJobs: Int? = null,
        concurrencyKey: String? = null,
    ): JobRecord {
        val resolvedMaxConcurrentJobs = maxConcurrentJobs ?: this.maxConcurrentJobs
        require(resolvedMaxConcurrentJobs == null || resolvedMaxConcurrentJobs > 0) {
            "Max concurrent jobs must be greater than 0."
        }
        require(resolvedMaxConcurrentJobs != null || concurrencyKey == null) {
            "A concurrency key requires a max concurrent jobs value."
        }

        val now = System.currentTimeMillis()
        return JobRecord(
            type = jobTypeIdentifier,
            createdAt = now,
            priority = jobPriority ?: 0,
            parentJobId = null,
            minimumStartAt = minimumStartAt,
            attempt = 1,
            estimatedRuntimeSeconds = null,
            state = JobState.PENDING,
            jobDetails = jobDetails,
            systemIdentifier = null,
            startedAt = null,
            lastCheckInAt = null,
            endedAt = null,
            failureDetails = null,
            scheduledJob = true,
            lockKey = lockKey,
            maxConcurrentJobs = resolvedMaxConcurrentJobs,
            concurrencyKey = concurrencyKey,
        )
    }
}
