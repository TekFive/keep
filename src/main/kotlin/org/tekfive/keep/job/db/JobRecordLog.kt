package org.tekfive.keep.job.db

import org.tekfive.keep.data.Data
import org.tekfive.keep.data.RecordsAddedAtData

class JobRecordLog(
    val jobRecordId: Long,
    val level: JobRecordLogLevel,
    val message: String,
    override val addedAt: Long = System.currentTimeMillis(),
) : Data(), RecordsAddedAtData
