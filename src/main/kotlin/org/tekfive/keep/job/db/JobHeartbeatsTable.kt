package org.tekfive.keep.job.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * One heartbeat per captured job. Keeping this row separate lets a transactional job check in
 * while its business transaction holds a lock on the job record. Include in KeepSchema.tables.
 */
object JobHeartbeatsTable : Table("job_heartbeats") {
    val jobRecordId = long("job_record_id").references(JobRecordsTable.id,
        onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.RESTRICT,
        fkName = "job_heartbeats_job_record_id_fk")
    val executionToken = javaUUID("execution_token")
    val lastCheckInAt = long("last_checkin_at").nullable()
    override val primaryKey = PrimaryKey(jobRecordId)
}
