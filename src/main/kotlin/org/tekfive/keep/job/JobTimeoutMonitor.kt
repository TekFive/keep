package org.tekfive.keep.job

import org.slf4j.LoggerFactory
import org.tekfive.keep.db.db
import org.tekfive.keep.job.db.DatabaseGatekeeper
import org.tekfive.keep.job.db.JobRecord
import org.tekfive.keep.job.db.JobRecordsTable
import java.sql.SQLException

internal class JobTimeoutMonitor(
    private val configuration: JobConfiguration,
    private val registry: JobRegistry,
    private val databaseGatekeeper: DatabaseGatekeeper,
    private val jobsTable: JobRecordsTable,
) {
    private val log = LoggerFactory.getLogger(JobTimeoutMonitor::class.java)

    fun sweep(now: Long = System.currentTimeMillis()) {
        val specsByType = registry.jobSpecs.associateBy { it.jobTypeIdentifier }
        if (specsByType.isEmpty()) return

        val runningJobs = try {
            databaseGatekeeper { db { jobsTable.getRunningJobs(specsByType.keys.toList()) } }
        } catch (e: SQLException) {
            databaseGatekeeper.onSQLException(e)
            return
        }

        for (jobRecord in runningJobs) {
            val spec = specsByType[jobRecord.type] ?: continue
            val timeout = expiredLimit(jobRecord, spec, now) ?: continue
            val failureDetails = when (timeout.reason) {
                JobTimeoutReason.HEARTBEAT -> "Job timed out after ${timeout.seconds} seconds without checking in."
                JobTimeoutReason.MAX_RUNTIME -> "Job exceeded its maximum runtime of ${timeout.seconds} seconds."
            }
            try {
                databaseGatekeeper {
                    // Commit the timeout and application cleanup together so failures can retry.
                    db {
                        val timedOutJob = jobsTable.tryMarkTimedOut(jobRecord.id, timeout.cutoffAt, now, failureDetails, timeout.reason)
                            ?: return@db
                        spec.onJobTimedOut(timedOutJob, now, timeout.seconds, timeout.reason)
                    }
                }
            } catch (e: SQLException) {
                databaseGatekeeper.onSQLException(e)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                log.warn("Job timeout handler failed for job {} of type {}; will retry.", jobRecord.id, jobRecord.type, e)
            }
        }
    }

    private fun expiredLimit(job: JobRecord, spec: JobSpec, now: Long): Timeout? {
        val maxRuntime = job.maxRuntimeSeconds ?: spec.maxRuntimeSeconds ?: configuration.defaultJobMaxRuntimeSeconds
        val runtimeCutoff = now - maxRuntime * MILLIS_PER_SECOND
        val startedAt = job.startedAt
        // Runtime wins when both limits expire; a fresh heartbeat cannot extend it.
        if (maxRuntime > 0 && startedAt != null && startedAt <= runtimeCutoff) {
            return Timeout(JobTimeoutReason.MAX_RUNTIME, maxRuntime, runtimeCutoff)
        }

        val heartbeat = job.timeoutSeconds ?: spec.timeoutSeconds ?: configuration.defaultJobTimeoutSeconds
        if (heartbeat <= 0) {
            return null
        }
        val lastActivityAt = job.lastCheckInAt ?: job.startedAt ?: return null
        val heartbeatCutoff = now - heartbeat * MILLIS_PER_SECOND
        if (lastActivityAt > heartbeatCutoff) {
            return null
        }
        return Timeout(JobTimeoutReason.HEARTBEAT, heartbeat, heartbeatCutoff)
    }

    private data class Timeout(val reason: JobTimeoutReason, val seconds: Int, val cutoffAt: Long)

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
