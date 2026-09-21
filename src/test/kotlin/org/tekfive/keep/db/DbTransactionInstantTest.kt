package org.tekfive.keep.db

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DbTransactionInstantTest {
    @Test
    fun `outside a transaction returns now without starting a transaction`() {
        assertFalse(inDbTransaction())
        val before = Instant.now()
        val actual = dbTransactionInstant()
        val after = Instant.now()
        assertTrue(actual >= before && actual <= after)
        assertFalse(inDbTransaction())
    }
}
