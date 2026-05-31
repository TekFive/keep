package org.tekfive.keep.job.ack

import org.tekfive.ack.Ack
import org.tekfive.keep.job.db.DatabaseRetrySettings

object AckDatabaseRetrySettings : DatabaseRetrySettings {
    val windowMillisProperty = Ack.long("JOB_DATABASE_RETRY_WINDOW_MILLISECONDS", 60_000, description = "Time window in milliseconds for counting job database retry errors.")

    val baseDelaySecondsProperty = Ack.double("JOB_DATABASE_RETRY_BASE_DELAY_SECONDS", 2.0, description = "Base backoff delay in seconds for job database retries.")

    val maxDelaySecondsProperty = Ack.double("JOB_DATABASE_RETRY_MAX_DELAY_SECONDS", 60.0, description = "Maximum backoff delay in seconds for job database retries.")

    override val windowMillis: Long
        get() = windowMillisProperty()

    override val baseDelaySeconds: Double
        get() = baseDelaySecondsProperty()

    override val maxDelaySeconds: Double
        get() = maxDelaySecondsProperty()

}
