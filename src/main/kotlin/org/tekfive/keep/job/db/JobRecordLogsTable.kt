package org.tekfive.keep.job.db

import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.dataEnum
import org.tekfive.keep.data.addedAt

object JobRecordLogsTable : DataTable<JobRecordLog>("job_record_logs") {
    val jobRecordId = long("job_record_id")
        .references(JobRecordsTable.id)
    val level = dataEnum<JobRecordLogLevel>("level")
    val message = text("message")
    val addedAt = addedAt()
}
