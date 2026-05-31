package org.tekfive.keep.job

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.tekfive.keep.db.db
import org.tekfive.keep.job.db.JobRecordLog
import org.tekfive.keep.job.db.JobRecordLogLevel
import org.tekfive.keep.job.db.JobRecordLogsTable

class JobLogger constructor(
    job: Job,
    private val context: JobContext,
    private val minSaveLevel: JobRecordLogLevel?,
) {
    private val slf4jLogger: Logger = LoggerFactory.getLogger(job.javaClass)

    fun debug(message: String) = log(JobRecordLogLevel.DEBUG, message)

    fun info(message: String): String  {
        log(JobRecordLogLevel.INFO, message)
        return message
    }

    fun warn(message: String, e: Throwable? = null): String {
        log(JobRecordLogLevel.WARN, message, e)
        return message
    }

    fun error(message: String, e: Throwable? = null): String {
        log(JobRecordLogLevel.ERROR, message, e)
        return message
    }

    private fun log(level: JobRecordLogLevel, message: String, e: Throwable? = null) {
        forwardToSlf4j(level, message, e)

        if (minSaveLevel != null && level.id >= minSaveLevel.id) {
            try {
                db {
                    JobRecordLogsTable.create(
                        JobRecordLog(
                            jobRecordId = context.jobId,
                            level = level,
                            message = message,
                        )
                    )
                }
            } catch (e: Exception) {
                slf4jLogger.error("Unable to record job log to database table due to ${e.message}", e)
            }
        }
    }

    private fun forwardToSlf4j(level: JobRecordLogLevel, message: String, e: Throwable? = null) {
        val prefixed = "[job:${context.jobId}] $message"
        when (level) {
            JobRecordLogLevel.DEBUG -> slf4jLogger.debug(prefixed)
            JobRecordLogLevel.INFO -> slf4jLogger.info(prefixed)
            JobRecordLogLevel.WARN -> slf4jLogger.warn(prefixed, e)
            JobRecordLogLevel.ERROR -> slf4jLogger.error(prefixed, e)
        }
    }
}
