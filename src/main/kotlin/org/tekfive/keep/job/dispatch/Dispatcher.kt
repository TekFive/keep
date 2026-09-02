package org.tekfive.keep.job.dispatch

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.tekfive.keep.job.*
import org.tekfive.keep.job.db.DatabaseGatekeeper
import org.tekfive.keep.job.db.JobRecord
import org.tekfive.keep.job.db.JobRecordsTable
import org.tekfive.keep.db.db
import java.sql.SQLException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicInteger

internal class Dispatcher(
    val jobConfiguration: JobConfiguration,
    val candidateQueue: BlockingQueue<Pair<Long, JobSpec>>,
    val activeDispatchers: Collection<Dispatcher>,
    val databaseGatekeeper: DatabaseGatekeeper,
    val jobsTable: JobRecordsTable,
    val systemIdentifier: String,
) : Thread() {

    private val log: Logger = LoggerFactory.getLogger(Dispatcher::class.java)

    init {
        name = "Job Dispatcher-${sequence.incrementAndGet()}"
        uncaughtExceptionHandler = UncaughtExceptionHandler { thread, e ->
            log.error("Job dispatcher thread {} died unexpectedly; the coordinator will replace it on its next poll.", thread.name, e)
        }
    }

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
                } catch (e: Exception) {
                    log.error("Unable to evaluate candidate job {}; it will be re-evaluated on a later poll.", jobId, e)
                    continue
                }

                if (jobRecord == null) {
                    // Job record was likely locked by another dispatcher.
                    continue
                }

                dispatch(jobRecord, jobSpec)
            } catch (_: InterruptedException) {
                // Interrupted while idle; the loop condition decides whether this dispatcher continues.
            }
        }
    }

    /**
     * Runs one captured job. Whatever happens after capture, the record is always moved out of
     * [JobState.RUNNING] by [finalizeJob] before this method returns, so a failure in job
     * construction, an [Error] thrown by the job, or an interrupt cannot orphan the record.
     */
    private fun dispatch(jobRecord: JobRecord, jobSpec: JobSpec) {
        var dispatchContext: DispatchContext? = null
        var result = ExecuteJobResult(ExecuteJobResultType.INTERRUPTED)
        var fatal: VirtualMachineError? = null
        try {
            val job = jobSpec.createJob()
            val context = DispatchContext(
                jobConfiguration.minSecondsBetweenJobCheckin,
                job,
                jobSpec,
                jobRecord,
                jobsTable,
                jobConfiguration.minSaveLogLevel,
            )
            dispatchContext = context

            result = databaseGatekeeper { executeJob(context) }
            if (result.type == ExecuteJobResultType.REDO_IMMEDIATELY_IF_ALLOWED) {
                // A redo is a new attempt: it is recorded as a failure and retried through a new
                // record so the retry limit and retry delay apply.
                result = ExecuteJobResult(ExecuteJobResultType.FAILED_BUT_RETRY_IF_ALLOWED, result.failureDetails)
            }
        } catch (_: InterruptedException) {
            result = ExecuteJobResult(ExecuteJobResultType.INTERRUPTED)
        } catch (e: VirtualMachineError) {
            fatal = e
            result = ExecuteJobResult(ExecuteJobResultType.FAILED, "Dispatch failed: ${e::class.simpleName}: ${e.message}")
        } catch (e: Throwable) {
            log.error("Job {} of type {} could not be dispatched.", jobRecord.id, jobRecord.type, e)
            result = ExecuteJobResult(ExecuteJobResultType.FAILED, "Dispatch failed: ${e::class.simpleName}: ${e.message}")
        } finally {
            finalizeCaptured(result, jobRecord, dispatchContext, jobSpec)
        }

        fatal?.let { throw it }
    }

    private fun finalizeCaptured(result: ExecuteJobResult, jobRecord: JobRecord, dispatchContext: DispatchContext?, jobSpec: JobSpec) {
        try {
            databaseGatekeeper.keepTrying { finalizeJob(result, jobRecord, dispatchContext, jobSpec) }
        } catch (_: InterruptedException) {
            // Stopping while the database is backing off: make one direct attempt rather than
            // leave the record running.
            try {
                finalizeJob(result, jobRecord, dispatchContext, jobSpec)
            } catch (e: Exception) {
                log.error("Unable to finalize job {} while stopping: {}", jobRecord.id, e.message, e)
            }
        } catch (e: Exception) {
            log.error("Unable to finalize job {}: {}", jobRecord.id, e.message, e)
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
        } catch (_: InterruptedException) {
            log.info("Job {} was interrupted before it completed and will be returned to pending.", dispatchContext.jobRecord.id)
            return ExecuteJobResult(ExecuteJobResultType.INTERRUPTED)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            dispatchContext.log.error("Job failed with ${e::class.simpleName}: ${e.message}", e)
            return if (dispatchContext.jobSpec.retryExceptionBaseTypes.any { it.isInstance(e) }) {
                ExecuteJobResult(ExecuteJobResultType.FAILED_BUT_RETRY_IF_ALLOWED, e.message)
            } else {
                ExecuteJobResult(ExecuteJobResultType.FAILED, e.message)
            }
        }
    }

    internal fun finalizeJob(result: ExecuteJobResult, dispatchContext: DispatchContext, jobSpec: JobSpec) {
        finalizeJob(result, dispatchContext.jobRecord, dispatchContext, jobSpec)
    }

    /**
     * Moves [jobRecord] out of [JobState.RUNNING] according to [result]. The state change and any
     * retry insert run in separate transactions: a failed retry insert must not roll back the
     * state change, which would leave the record running.
     */
    internal fun finalizeJob(result: ExecuteJobResult, jobRecord: JobRecord, dispatchContext: DispatchContext?, jobSpec: JobSpec) {
        val endedAt = System.currentTimeMillis()

        when (result.type) {
            ExecuteJobResultType.ALREADY_TERMINATED -> {}
            ExecuteJobResultType.SUCCESS -> {
                db { jobsTable.tryMarkEnded(jobRecord.id, endedAt, JobState.COMPLETED) }
            }
            ExecuteJobResultType.NEED_USER_INPUT -> {
                db { jobsTable.tryMarkEnded(jobRecord.id, endedAt, JobState.WAITING) }
            }
            ExecuteJobResultType.FAILED -> {
                db { jobsTable.tryMarkEnded(jobRecord.id, endedAt, JobState.FAILED, result.failureDetails) }
            }
            ExecuteJobResultType.INTERRUPTED -> {
                val requeued = db { jobsTable.tryRequeue(jobRecord.id) }
                if (requeued) {
                    log.info("Job {} of type {} was returned to pending after an interrupted dispatch.", jobRecord.id, jobRecord.type)
                }
            }
            ExecuteJobResultType.FAILED_BUT_RETRY_IF_ALLOWED,
            ExecuteJobResultType.REDO_IMMEDIATELY_IF_ALLOWED -> {
                val markedEnded = db { jobsTable.tryMarkEnded(jobRecord.id, endedAt, JobState.FAILED, result.failureDetails) }
                if (markedEnded && dispatchContext != null && canRetry(dispatchContext, jobSpec)) {
                    scheduleRetry(dispatchContext, jobSpec)
                }
            }
        }
    }

    private fun scheduleRetry(dispatchContext: DispatchContext, jobSpec: JobSpec) {
        val minSeconds = (jobSpec.minSecondsBetweenRetries ?: jobConfiguration.defaultMinSecondsBetweenJobRetry)
        val minStartAt = if (minSeconds > 0) {
            minSeconds * 1000L + System.currentTimeMillis()
        } else {
            null
        }

        try {
            databaseGatekeeper.keepTrying {
                db {
                    jobsTable.insertJob(
                        jobSpec,
                        parentJobContext = dispatchContext,
                        minStartAt = minStartAt,
                        maxEstimatedRuntimeRecords = jobConfiguration.maxEstimatedRuntimeRecords,
                    )
                }
            }
        } catch (e: SQLException) {
            log.error("Unable to insert retry job for {}; the retry is lost.", dispatchContext.jobRecord.id, e)
        } catch (e: Exception) {
            log.error("Unable to prepare retry job for {}; the retry is lost.", dispatchContext.jobRecord.id, e)
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

    companion object {
        private val sequence = AtomicInteger()
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
    INTERRUPTED,
}
