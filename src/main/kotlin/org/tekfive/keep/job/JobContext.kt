package org.tekfive.keep.job

import org.tekfive.jfk.JsonObject
import java.util.UUID

interface JobContext {
    val jobId: Long
    val startedAt: Long
    val type: String
    val createdAt: Long
    val attempt: Int
    val maxRetries: Int
    val estimatedRuntimeSeconds: Int?
    val details: JsonObject?
    val log: JobLogger

    val trigger: JobTrigger
        get() = JobTrigger.system()

    val subject: JobSubject?
        get() = null

    /** Available for persisted jobs; custom contexts may omit it. */
    val runId: UUID?
        get() = null

    val tenantId: String?
        get() = null

    val applicationVersion: String?
        get() = null

    val processedCount: Long?
        get() = null

    val failedCount: Long?
        get() = null

    val totalCount: Long?
        get() = null

    val hasMoreRetries: Boolean
        get() = attempt <= maxRetries

    fun checkIn(now: Long = System.currentTimeMillis())

    fun updateDetails(details: JsonObject)

    /** Saves absolute counts for this attempt. Omitted values are retained; explicit null clears one. */
    fun updateProgress(
        processedCount: Long? = this.processedCount,
        failedCount: Long? = this.failedCount,
        totalCount: Long? = this.totalCount,
    ) {
        throw UnsupportedOperationException("This job context does not support progress updates.")
    }
}
