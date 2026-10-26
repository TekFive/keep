package org.tekfive.keep.job

import org.tekfive.jfk.JsonObject

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

    /** User who initiated the job, or null for a system job. */
    val userId: String?
        get() = null

    val hasMoreRetries: Boolean
        get() = attempt <= maxRetries

    fun checkIn(now: Long = System.currentTimeMillis())

    fun updateDetails(details: JsonObject)
}
