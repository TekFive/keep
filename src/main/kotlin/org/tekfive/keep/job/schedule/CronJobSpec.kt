package org.tekfive.keep.job.schedule

import org.tekfive.keep.job.db.JobRecord
import java.time.ZoneId

interface CronJobSpec : ScheduledJobSpec {

    val cronExpression: CronExpression

    val cronZone: ZoneId
        get() = ZoneId.systemDefault()

    override fun schedule(lastEndedAt: Long?): JobRecord {
        val after = lastEndedAt ?: System.currentTimeMillis()
        val nextTime = cronExpression.nextAfter(after, cronZone)
        return createScheduledJob(nextTime)
    }
}
