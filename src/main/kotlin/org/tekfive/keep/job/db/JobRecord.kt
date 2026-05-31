package org.tekfive.keep.job.db

import org.tekfive.jfk.JsonObject
import org.tekfive.keep.data.Data
import org.tekfive.keep.data.RecordsAddedAtData
import org.tekfive.keep.job.JobState

class JobRecord(
    val type: String,
    val createdAt: Long,
    val priority: Int,
    val parentJobId: Long?,
    val minimumStartAt: Long?,
    val attempt: Int,
    var estimatedRuntimeSeconds: Int?,
    var state: JobState,
    var jobDetails: JsonObject?,
    var systemIdentifier: String?,
    var startedAt: Long?,
    var lastCheckInAt: Long?,
    var endedAt: Long?,
    var failureDetails: String? = null,
    val scheduledJob: Boolean = false,
    val lockKey: String? = null,
    val maxConcurrentJobs: Int? = null,
    val concurrencyKey: String? = null,
) : Data() {
}
