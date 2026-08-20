package org.tekfive.keep.data

/** PostgreSQL storage representations available for a [java.time.Instant] property. */
enum class InstantStorage {
    /** Epoch milliseconds stored as `BIGINT`. This is KEEP's default timestamp representation. */
    BIGINT_EPOCH_MILLIS,

    /** A native PostgreSQL `TIMESTAMP WITH TIME ZONE` value. */
    TIMESTAMP_WITH_TIME_ZONE,
}
