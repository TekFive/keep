package org.tekfive.keep.job

/** Identifies the expired limit so application cleanup can report the cause. */
enum class JobTimeoutReason {
    HEARTBEAT,
    MAX_RUNTIME,
}
