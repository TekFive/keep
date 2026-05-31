package org.tekfive.keep.job.dispatch

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.tekfive.keep.job.*
import org.tekfive.keep.job.db.DatabaseGatekeeper
import org.tekfive.keep.job.db.JobRecordsTable
import org.tekfive.keep.db.db
import java.sql.SQLException
import java.util.concurrent.BlockingQueue

internal class Dispatcher(
    val jobConfiguration: JobConfiguration,
    val candidateQueue: BlockingQueue<Pair<Long, JobSpec>>,
    val activeDispatchers: Collection<Dispatcher>,
    val databaseGatekeeper: DatabaseGatekeeper,
    val jobsTable: JobRecordsTable,
    val systemIdentifier: String,
) : Thread() {

    private val log: Logger = LoggerFactory.getLogger(Dispatcher::class.java)

    override fun run() {
        while (activeDispatchers.contains(this)) {
            try {
                val (jobId, jobSpec) = databaseGatekeeper { candidateQueue.take() }
                val jobRecord = try {
                    db { jobsTable.tryCaptureRunLock(jobId, systemIdentifier, jobSpec) }
                } catch (e: SQLException) {
                    log.warn("SQL exception occurred while trying to capture job record lock.", e)
                    databaseGatekeeper.onSQLException(e)
                    continue
                }

                if (jobRecord == null) {
                    // Job record was likely locked by another dispatcher.
                    continue
                }

                val job = jobSpec.createJob()
                val dispatchContext = DispatchContext(
                    jobConfiguration.minSecondsBetweenJobCheckin,
                    job,
                    jobSpec,
                    jobRecord,
                    jobsTable,
                    jobConfiguration.minSaveLogLevel,
                )

                var result: ExecuteJobResult
                do {
                    result = databaseGatekeeper { executeJob(dispatchContext) }
                    if (result.type == ExecuteJobResultType.REDO_IMMEDIATELY_IF_ALLOWED) {
                        if (jobSpec.maxRetriesOnFailure <= 0) {
                            result = ExecuteJobResult(ExecuteJobResultType.FAILED)
                        }
                    }
                } while (result.type == ExecuteJobResultType.REDO_IMMEDIATELY_IF_ALLOWED)

                try {
                    databaseGatekeeper.keepTrying { finalizeJob(result, dispatchContext, jobSpec) }
                } catch (e: SQLException) {
                    log.error("Unable to finalize job: ${jobRecord.id} SQL exception: ${e.message}.", e)
                }
            } catch(e: InterruptedException) {}
        }
    }

    internal fun executeJob(dispatchContext: DispatchContext): ExecuteJobResult {
        try {
            val result = try {
                dispatchContext.job.execute(dispatchContext)
            } catch (result: JobResult) {
                result
            }

            return when (result) {
                is JobCompleted -> {
                    if (!result.infoMessage.isNullOrBlank()) {
                        dispatchContext.log.info(result.infoMessage)
                    }
                    ExecuteJobResult(ExecuteJobResultType.SUCCESS)
                }
                is JobWaiting -> {
                    if (!result.infoMessage.isNullOrBlank()) {
                        dispatchContext.log.info(result.infoMessage)
                    }
                    ExecuteJobResult(ExecuteJobResultType.NEED_USER_INPUT)
                }
                is JobFailed -> {
                    if (!result.errorMessage.isNullOrBlank()) {
                        dispatchContext.log.error(result.errorMessage)
                    }
                    if (result.retryIfAllowed) {
                        ExecuteJobResult(ExecuteJobResultType.FAILED_BUT_RETRY_IF_ALLOWED, result.errorMessage)
                    } else {
                        ExecuteJobResult(ExecuteJobResultType.FAILED, result.errorMessage)
                    }
                }
            }
        } catch (e: OnCheckInSqlException) {
            return if (databaseGatekeeper.onSQLException(e.sqlException)) {
                dispatchContext.log.error("SQL Exception on job checkin ${e.sqlException.message}", e)
                ExecuteJobResult(ExecuteJobResultType.REDO_IMMEDIATELY_IF_ALLOWED, e.sqlException.message)
            } else {
                ExecuteJobResult(ExecuteJobResultType.FAILED, e.sqlException.message)
            }
        } catch (e: JobEndedException) {
            log.info("Job {} ended externally: {}", dispatchContext.jobRecord.id, e.message)
            return ExecuteJobResult(ExecuteJobResultType.ALREADY_TERMINATED)
        } catch (e: Exception) {
            return if (dispatchContext.jobSpec.retryExceptionBaseTypes.any { it.isInstance(e) }) {
                ExecuteJobResult(ExecuteJobResultType.FAILED_BUT_RETRY_IF_ALLOWED, e.message)
            } else {
                ExecuteJobResult(ExecuteJobResultType.FAILED, e.message)
            }
        }
    }

    internal fun finalizeJob(result: ExecuteJobResult, dispatchContext: DispatchContext, jobSpec: JobSpec) {
        if (result.type == ExecuteJobResultType.ALREADY_TERMINATED) {
            return
        }

        val endedAt = System.currentTimeMillis()

        db {
            when (result.type) {
                ExecuteJobResultType.SUCCESS -> {
                    jobsTable.tryMarkEnded(dispatchContext.jobRecord.id, endedAt, JobState.COMPLETED)
                }
                ExecuteJobResultType.NEED_USER_INPUT -> {
                    jobsTable.tryMarkEnded(dispatchContext.jobRecord.id, endedAt, JobState.WAITING)
                }
                ExecuteJobResultType.FAILED -> {
                    jobsTable.tryMarkEnded(dispatchContext.jobRecord.id, endedAt, JobState.FAILED, result.failureDetails)
                }
                ExecuteJobResultType.FAILED_BUT_RETRY_IF_ALLOWED -> {
                    val markedEnded = jobsTable.tryMarkEnded(dispatchContext.jobRecord.id, endedAt, JobState.FAILED, result.failureDetails)
                    if (markedEnded && canRetry(dispatchContext, jobSpec)) {
                        try {
                            val minSeconds = (jobSpec.minSecondsBetweenRetries ?: jobConfiguration.defaultMinSecondsBetweenJobRetry)
                            val minStartAt = if (minSeconds > 0) {
                                minSeconds * 1000L + System.currentTimeMillis()
                            } else {
                                null
                            }

                            jobsTable.insertJob(
                                jobSpec,
                                parentJobContext = dispatchContext,
                                minStartAt = minStartAt,
                                maxEstimatedRuntimeRecords = jobConfiguration.maxEstimatedRuntimeRecords,
                            )
                        } catch (e: SQLException) {
                            log.warn("SQL exception while inserting retry job for {}.", dispatchContext.jobRecord.id, e)
                            databaseGatekeeper.onSQLException(e)
                        }
                    }
                }
                else -> {}
            }
        }
    }

    internal fun canRetry(jobContext: JobContext, jobSpec: JobSpec, cause: Exception? = null): Boolean {
        val maxRetries = jobSpec.maxRetriesOnFailure
        return if (maxRetries <= 0) {
            false
        } else {
            val retryCount = jobContext.attempt - 1
            if (retryCount >= maxRetries) {
                false
            } else if (cause == null) {
                true
            } else {
                jobSpec.retryExceptionBaseTypes.any { it.isInstance(cause) }
            }
        }
    }
}

internal class ExecuteJobResult(val type: ExecuteJobResultType, val failureDetails: String? = null)

internal enum class ExecuteJobResultType {
    REDO_IMMEDIATELY_IF_ALLOWED,
    SUCCESS,
    FAILED,
    FAILED_BUT_RETRY_IF_ALLOWED,
    NEED_USER_INPUT,
    ALREADY_TERMINATED,
}
