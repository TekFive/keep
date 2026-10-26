package org.tekfive.keep.job

sealed class JobResult(
    val processedCount: Long? = null,
    val failedCount: Long? = null,
    val totalCount: Long? = null,
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
) : JobResult(processedCount, failedCount, totalCount)

class JobCompleted(
    val infoMessage: String? = null,
    processedCount: Long? = null,
    failedCount: Long? = null,
    totalCount: Long? = null,
) : JobResult(processedCount, failedCount, totalCount)

class JobFailed(
    val errorMessage: String? = null,
    val retryIfAllowed: Boolean = false,
    processedCount: Long? = null,
    failedCount: Long? = null,
    totalCount: Long? = null,
) : JobResult(processedCount, failedCount, totalCount)

internal fun validateJobCounts(processedCount: Long?, failedCount: Long?, totalCount: Long?) {
    require(processedCount == null || processedCount >= 0) { "Processed count must not be negative." }
    require(failedCount == null || failedCount >= 0) { "Failed count must not be negative." }
    require(totalCount == null || totalCount >= 0) { "Total count must not be negative." }
}
