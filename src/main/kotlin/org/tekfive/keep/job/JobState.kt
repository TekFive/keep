package org.tekfive.keep.job

import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.DataEnumColumnType

enum class JobState(override val id: Int, override val displayName: String, val terminated: Boolean) : DataEnum {
    PENDING(1, "Waiting", false),
    RUNNING(2, "Running", false),
    COMPLETED(3, "Succeeded", true),
    FAILED(4, "Failed", true),
    CANCELLED(5, "Cancelled", true),
    TIMED_OUT(6, "Timed Out", true),
    WAITING(7, "Need User Input", true),
    UNKNOWN(8, "Unknown", true),
    ;

    companion object : DataEnumColumnType<JobState>() {
        val waitingStates = dataEnumValues.filter { !it.terminated && it != RUNNING }
        val terminatedStates = dataEnumValues.filter { it.terminated }
    }
}
