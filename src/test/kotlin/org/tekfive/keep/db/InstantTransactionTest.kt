package org.tekfive.keep.db

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InstantTransactionTest {
    @Test
    fun `outside a transaction returns now and ignores the receiver`() {
        assertFalse(inDbTransaction())
        val before = Instant.now()
        val actual = Instant.EPOCH.transaction()
        val after = Instant.now()
        assertTrue(actual >= before && actual <= after)
        assertNotEquals(Instant.EPOCH, actual)
        assertFalse(inDbTransaction())
    }
}
