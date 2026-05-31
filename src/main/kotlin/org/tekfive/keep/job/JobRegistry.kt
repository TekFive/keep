package org.tekfive.keep.job

import org.tekfive.keep.job.schedule.ScheduledJobSpec

class JobRegistry() {

    private val descriptionsByTypeId = mutableMapOf<String, JobSpec>()
    private val lock = Any()

    val jobTypeIds: List<String>
        get() = synchronized(lock) { descriptionsByTypeId.keys.toList() }

    val jobSpecs: List<JobSpec>
        get() = synchronized(lock) { descriptionsByTypeId.values.toList() }

    val scheduledJobSpecs: List<ScheduledJobSpec>
        get() = synchronized(lock) { descriptionsByTypeId.values.filterIsInstance<ScheduledJobSpec>() }

    operator fun get(jobTypeId: String): JobSpec? {
        return synchronized(lock) { descriptionsByTypeId[jobTypeId] }
    }

    operator fun plusAssign(jobSpec: JobSpec) {
        register(jobSpec)
    }

    fun register(jobSpec: JobSpec) {
        synchronized(lock) {
            require(!descriptionsByTypeId.containsKey(jobSpec.jobTypeIdentifier)) { "Job type identifier ${jobSpec.jobTypeIdentifier} is already registered." }
            descriptionsByTypeId[jobSpec.jobTypeIdentifier] = jobSpec
        }
    }
}
