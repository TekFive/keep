package org.tekfive.keep.job

import org.slf4j.LoggerFactory
import org.tekfive.keep.db.db
import org.tekfive.keep.job.db.DatabaseGatekeeper
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
            val timeoutSeconds = spec.timeoutSeconds ?: configuration.defaultJobTimeoutSeconds
            if (timeoutSeconds <= 0) continue

            val lastActivityAt = jobRecord.lastCheckInAt ?: jobRecord.startedAt ?: continue
            val cutoffAt = now - timeoutSeconds * 1000L
            if (lastActivityAt > cutoffAt) continue

            val failureDetails = "Job timed out after $timeoutSeconds seconds without checking in."
            val timedOutJob = try {
                databaseGatekeeper {
                    db { jobsTable.tryMarkTimedOut(jobRecord.id, cutoffAt, now, failureDetails) }
                }
            } catch (e: SQLException) {
                databaseGatekeeper.onSQLException(e)
                null
            } ?: continue

            try {
                spec.onJobTimedOut(timedOutJob, now, timeoutSeconds)
            } catch (e: Exception) {
                log.warn("Job timeout handler failed for job {} of type {}.", timedOutJob.id, timedOutJob.type, e)
            }
        }
    }
}
