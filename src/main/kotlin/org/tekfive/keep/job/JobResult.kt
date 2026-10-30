package org.tekfive.keep.job

import org.tekfive.jfk.JsonObject

sealed class JobResult(
    val processedCount: Long? = null,
    val failedCount: Long? = null,
    val totalCount: Long? = null,
    /** Structured outcome saved to the job record when the attempt ends. */
    val result: JsonObject? = null,
) : Exception() {
    init {
        validateJobCounts(processedCount, failedCount, totalCount)
    }
}

class JobWaiting(
    val infoMessage: String? = null,
    processedCount: Long? = null,
    failedCount: Long? = null,
    totalCount: Long? = null,
    result: JsonObject? = null,
) : JobResult(processedCount, failedCount, totalCount, result)

class JobCompleted(
    val infoMessage: String? = null,
    processedCount: Long? = null,
    failedCount: Long? = null,
    totalCount: Long? = null,
    result: JsonObject? = null,
) : JobResult(processedCount, failedCount, totalCount, result)

class JobFailed(
    val errorMessage: String? = null,
    val retryIfAllowed: Boolean = false,
    processedCount: Long? = null,
    failedCount: Long? = null,
    totalCount: Long? = null,
    result: JsonObject? = null,
) : JobResult(processedCount, failedCount, totalCount, result)

internal fun validateJobCounts(processedCount: Long?, failedCount: Long?, totalCount: Long?) {
    require(processedCount == null || processedCount >= 0) { "Processed count must not be negative." }
    require(failedCount == null || failedCount >= 0) { "Failed count must not be negative." }
    require(totalCount == null || totalCount >= 0) { "Total count must not be negative." }
}
