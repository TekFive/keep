package org.tekfive.keep.job.schedule

import org.tekfive.ack.Ack
import org.tekfive.ack.ackNamespace
import org.tekfive.keep.job.db.JobRecord

interface FixedIntervalJobSpec : ScheduledJobSpec {

    val intervalSeconds: Long
        get() = intervalSecondsProperty().also {
            require(it > 0) { "intervalSeconds must be positive" }
        }

    val runImmediatelyOnFirstSchedule: Boolean
        get() = false

    val defaultInternalSeconds: Long
        get() = 3600L

    val intervalSecondsProperty: Ack<Long>
        get() = Ack.long("FIXED_INTERVAL_SECONDS", defaultInternalSeconds, namespace = ackNamespace(getNamespaceClass()), description = "Interval in seconds between runs of this fixed-interval job.")

    override fun schedule(lastEndedAt: Long?): JobRecord {
        val intervalMillis = intervalSeconds * 1000L
        val minimumStartAt = if (lastEndedAt == null && runImmediatelyOnFirstSchedule) {
            null
        } else {
            (lastEndedAt ?: System.currentTimeMillis()) + intervalMillis
        }
        return createScheduledJob(minimumStartAt)
    }
}
