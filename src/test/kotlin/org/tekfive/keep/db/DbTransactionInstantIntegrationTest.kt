package org.tekfive.keep.db

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DbTransactionInstantIntegrationTest {
    private lateinit var database: Database

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
    }

    private fun JdbcTransaction.serverTimestamp(): Instant = checkNotNull(
        exec("SELECT transaction_timestamp()") { result ->
            check(result.next())
            result.getTimestamp(1).toInstant()
        },
    )

    @Test
    fun `returns the start time even when first called later in a raw Exposed transaction`() = transaction(database) {
        val start = serverTimestamp()
        exec("SELECT pg_sleep(0.02)")
        assertEquals(start, dbTransactionInstant())
        assertEquals(start, dbTransactionInstant())
    }

    @Test
    fun `KEEP db blocks and nested blocks share the server transaction timestamp`() {
        db(cache = false) {
            val start = dbTransactionInstant()
            db(cache = false) {
                assertEquals(start, dbTransactionInstant())
            }
            assertEquals(start, dbTransactionInstant())
        }
        assertFalse(inDbTransaction())
        val before = Instant.now()
        assertTrue(dbTransactionInstant() >= before)
    }

    @Test
    fun `uses a fresh timestamp after a manual commit in the same Exposed transaction object`() = transaction(database) {
        val start = dbTransactionInstant()
        exec("SELECT pg_sleep(0.02)")
        commit()
        val next = dbTransactionInstant()
        assertTrue(next > start)
        assertEquals(serverTimestamp(), next)
    }

    @Test
    fun `uses a fresh timestamp after rollback`() {
        val first = transaction(database) {
            val start = dbTransactionInstant()
            exec("SELECT pg_sleep(0.02)")
            rollback()
            start
        }
        transaction(database) {
            val next = dbTransactionInstant()
            assertTrue(next > first)
            assertEquals(serverTimestamp(), next)
        }
    }
}
