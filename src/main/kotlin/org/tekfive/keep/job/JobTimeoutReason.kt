package org.tekfive.keep.job

import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.DataEnumColumnType

/** Identifies the expired limit so application cleanup can report the cause. */
enum class JobTimeoutReason(override val id: Int) : DataEnum {
    HEARTBEAT(1),
    MAX_RUNTIME(2);

    companion object : DataEnumColumnType<JobTimeoutReason>()
}
