package org.tekfive.keep.job

import org.tekfive.jfk.JsonObject
import org.tekfive.keep.job.db.JobRecord
import org.tekfive.keep.job.db.QueryNode
import kotlin.reflect.KClass

interface JobSpec {

    val jobTypeIdentifier: String
        get() = getNamespaceClass().simpleName
            ?: error("Cannot resolve simple name for ${getNamespaceClass()}")

    fun createJob(): Job

    val estimateRuntime: Boolean
        get() = true

    val jobPriority: Int?
        get() = null

    val maxRetriesOnFailure: Int
        get() = 0

    val minSecondsBetweenRetries: Int?
        get() = null

    /**
     * Timeout threshold for a running job with no check-in activity. A null value
     * uses [JobConfiguration.defaultJobTimeoutSeconds]. Values less than or equal
     * to zero disable timeout handling for this job type.
     */
    val timeoutSeconds: Int?
        get() = null

    val retryExceptionBaseTypes: List<KClass<out Exception>>
        get() = emptyList()

    fun getEstimatedRuntimeQueries(jobDetails: JsonObject): List<QueryNode> {
        return emptyList()
    }

    fun getNamespaceClass(): KClass<*> {
        return this::class.java.enclosingClass?.kotlin ?: this::class
    }

    /**
     * When true, the framework guarantees at most one job of this type
     * is in RUNNING state at any time, regardless of lock key or job details.
     * Other jobs of this type remain in WAITING_FOR_START until
     * the running instance completes.
     */
    val exclusiveExecution: Boolean
        get() = false

    /**
     * Maximum number of jobs of this type allowed to be in RUNNING state at once.
     *
     * When a job record also has a concurrency key, the limit applies to jobs with the
     * same type and key. Without a key, the limit applies to all jobs of this type.
     */
    val maxConcurrentJobs: Int?
        get() = if (exclusiveExecution) 1 else null

    fun onJobTimedOut(jobRecord: JobRecord, timedOutAt: Long, timeoutSeconds: Int) {
    }
}
