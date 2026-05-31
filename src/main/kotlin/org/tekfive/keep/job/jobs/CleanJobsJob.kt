package org.tekfive.keep.job.jobs

import org.tekfive.jfk.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.tekfive.ack.Ack
import org.tekfive.ack.ackNamespace
import org.tekfive.keep.db.db
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobResult
import org.tekfive.keep.job.JobState
import org.tekfive.keep.job.JobCompleted
import org.tekfive.keep.job.db.JobRecordsTable
import org.tekfive.keep.job.db.QueryNode
import org.tekfive.keep.job.schedule.FixedIntervalJobSpec
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.days

class CleanJobsJob : Job {

    companion object : FixedIntervalJobSpec {
        override val estimateRuntime = false
        override val jobTypeIdentifier = "clean-jobs"
        override val jobPriority: Int? = null
        override val maxRetriesOnFailure: Int = 0
        override val minSecondsBetweenRetries: Int? = null
        override val retryExceptionBaseTypes: List<KClass<out Exception>> = emptyList()

        override fun getEstimatedRuntimeQueries(jobDetails: JsonObject): List<QueryNode> = emptyList()

        override fun createJob(): Job = CleanJobsJob()

        override val intervalSecondsProperty: Ack<Long>
            get() = Ack.long("FIXED_INTERVAL_SECONDS", 24L * 60 * 60, namespace = ackNamespace(getNamespaceClass()), description = "Interval in seconds between job-table cleanup runs.")

        val deleteAfterDays = Ack.int("CLEAN_JOBS_DELETE_AFTER_DAYS", 2, description = "Age in days after which completed job records are deleted.")
    }

    override fun execute(context: JobContext): JobResult {
        val deleteCutoff = System.currentTimeMillis() -
            deleteAfterDays().days.inWholeMilliseconds

        db {
            JobRecordsTable.deleteWhere {
                (JobRecordsTable.state inList JobState.terminatedStates) and
                        JobRecordsTable.endedAt.isNotNull() and
                        (JobRecordsTable.endedAt lessEq deleteCutoff)
            }
        }

        return JobCompleted()
    }
}
