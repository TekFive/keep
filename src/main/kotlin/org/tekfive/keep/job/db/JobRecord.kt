package org.tekfive.keep.job.db

import org.tekfive.jfk.JsonObject
import org.tekfive.keep.data.Data
import org.tekfive.keep.job.JobState
import org.tekfive.keep.job.JobSubject
import org.tekfive.keep.job.JobTimeoutReason
import org.tekfive.keep.job.JobTrigger
import org.tekfive.keep.job.validateJobCounts
import java.util.UUID

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
    val trigger: JobTrigger = JobTrigger.system(),
    val lockKey: String? = null,
    val maxConcurrentJobs: Int? = null,
    val concurrencyKey: String? = null,
    /** Null inherits the type/global limit; non-positive disables heartbeat timeout. */
    val timeoutSeconds: Int? = null,
    /** Maximum elapsed seconds per attempt. Null inherits; non-positive disables the limit. */
    val maxRuntimeSeconds: Int? = null,
    val subject: JobSubject? = null,
    var resultSummary: String? = null,
    /** Version of the application that captured this attempt, supplied by JobConfiguration. */
    var applicationVersion: String? = null,
    /** Shared by retries and copies; each newly scheduled run gets a new ID. */
    val runId: UUID = UUID.randomUUID(),
    var timeoutReason: JobTimeoutReason? = null,
    val tenantId: String? = null,
    /** Number of items attempted, including failed items, in this attempt. Null means unknown. */
    var processedCount: Long? = null,
    var failedCount: Long? = null,
    var totalCount: Long? = null,
    /** Changes every time a worker captures this record, including after a requeue. */
    val executionToken: UUID? = null,
    /** Durable request to create a retry with this minimum start time. Null means no request. */
    val retryAt: Long? = null,
) : Data() {
    init {
        validateJobCounts(processedCount, failedCount, totalCount)
    }

    // The pinned JFK serializer needs UUID values converted explicitly.
    override fun toJsonObject(): JsonObject =
        toJsonObjectExcluding(JobRecord::runId, JobRecord::executionToken) +
            mapOf("runId" to runId.toString(), "executionToken" to executionToken?.toString())
}
