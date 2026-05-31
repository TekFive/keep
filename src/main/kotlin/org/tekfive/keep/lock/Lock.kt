package org.tekfive.keep.lock

import org.tekfive.keep.data.Data

class Lock(
    val lockId: String,
    val lastLockAt: Long?
) : Data() {
}