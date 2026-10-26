package org.tekfive.keep.job.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.data.ColumnGroup
import org.tekfive.keep.data.dataEnum
import org.tekfive.keep.job.JobSubject
import org.tekfive.keep.job.JobTrigger
import org.tekfive.keep.job.JobTriggerSource
import org.tekfive.keep.utils.ColumnValueMapper

class JobTriggerColumns(table: Table) : ColumnGroup<JobTrigger> {
    val source = table.run { dataEnum<JobTriggerSource>("trigger_source").default(JobTriggerSource.SYSTEM) }
    val userId = table.run { text("trigger_user_id").nullable() }
    override val columns: List<Column<*>> = listOf(source, userId)

    override fun map(row: ResultRow) = JobTrigger(row[source], row[userId])

    override fun mapColumns(value: JobTrigger, statement: ColumnValueMapper) {
        statement[source] = value.source
        statement[userId] = value.userId
    }
}

class JobSubjectColumns(table: Table) : ColumnGroup<JobSubject?> {
    val subjectType = table.run { text("subject_type").nullable() }
    val subjectId = table.run { text("subject_id").nullable() }
    override val columns: List<Column<*>> = listOf(subjectType, subjectId)

    override fun map(row: ResultRow): JobSubject? {
        val type = row[subjectType]
        val id = row[subjectId]
        if (type == null && id == null) return null
        return JobSubject(checkNotNull(type) { "Missing job subject type" }, checkNotNull(id) { "Missing job subject ID" })
    }

    override fun mapColumns(value: JobSubject?, statement: ColumnValueMapper) {
        statement[subjectType] = value?.subjectType
        statement[subjectId] = value?.subjectId
    }
}
