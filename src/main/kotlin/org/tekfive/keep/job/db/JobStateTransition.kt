package org.tekfive.keep.job.db

import org.tekfive.keep.data.Data
import org.tekfive.keep.job.JobState
import org.tekfive.keep.job.JobTimeoutReason

/** State and reporting values captured atomically by the job-record database trigger. */
class JobStateTransition(
    val jobRecordId: Long,
    /** Null only for the initial state recorded when a job row is inserted. */
    val fromState: JobState?,
    val toState: JobState,
    val transitionedAt: Long,
    val systemIdentifier: String?,
    val applicationVersion: String?,
    val resultSummary: String?,
    val timeoutReason: JobTimeoutReason?,
    val processedCount: Long?,
    val failedCount: Long?,
    val totalCount: Long?,
) : Data()
