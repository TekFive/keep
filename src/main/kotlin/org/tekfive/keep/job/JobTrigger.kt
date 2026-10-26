package org.tekfive.keep.job

import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.DataEnumColumnType
import org.tekfive.jfk.ToJsonObject

/** How a logical job run was initiated. Retries retain the original trigger. */
data class JobTrigger(val source: JobTriggerSource, val userId: String? = null) : ToJsonObject {
    init {
        require(source != JobTriggerSource.USER || userId != null) { "A user trigger requires a user ID." }
    }

    companion object {
        fun system() = JobTrigger(JobTriggerSource.SYSTEM)
        fun user(userId: String) = JobTrigger(JobTriggerSource.USER, userId)
        fun scheduled(userId: String? = null) = JobTrigger(JobTriggerSource.SCHEDULE, userId)
        fun event(userId: String? = null) = JobTrigger(JobTriggerSource.EVENT, userId)
    }
}

enum class JobTriggerSource(override val id: Int) : DataEnum {
    SYSTEM(1),
    USER(2),
    SCHEDULE(3),
    EVENT(4);

    companion object : DataEnumColumnType<JobTriggerSource>()
}
