package org.tekfive.keep.job.db

import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.DataEnumColumnType

enum class JobRecordLogLevel(override val id: Int, override val displayName: String) : DataEnum {
    DEBUG(1, "Debug"),
    INFO(2, "Info"),
    WARN(3, "Warn"),
    ERROR(4, "Error"),
    ;

    companion object : DataEnumColumnType<JobRecordLogLevel>()
}
