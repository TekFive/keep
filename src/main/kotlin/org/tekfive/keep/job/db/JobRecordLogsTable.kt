package org.tekfive.keep.job.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.dataEnum
import org.tekfive.keep.data.addedAt
import org.tekfive.keep.data.fkey

object JobRecordLogsTable : DataTable<JobRecordLog>("job_record_logs") {
    // Indexed so that deleting the logs of purged job records does not scan the whole table.
    // Deletes are cascaded by JobRecordCleaner rather than the database, so no ON DELETE action.
    val jobRecordId = fkey("job_record_id", JobRecordsTable, ReferenceOption.NO_ACTION)
    val level = dataEnum<JobRecordLogLevel>("level")
    val message = text("message")
    val addedAt = addedAt()
}
