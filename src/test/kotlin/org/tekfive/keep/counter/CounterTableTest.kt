package org.tekfive.keep.counter

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.db.db
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CounterTableTest {
    private val customTable = CounterTable("custom_counters")

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction { SchemaUtils.create(CountersTable, customTable) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(customTable, CountersTable) }
    }

    @Test
    fun `fixed windows reset at expiry and keep their original expiry until then`() {
        assertEquals(CounterTable.Counter(1, 1000, 2000), CountersTable.increment("quota", "a", 1000, now = 1000))
        assertEquals(CounterTable.Counter(3, 1000, 2000), CountersTable.increment("quota", "a", 1000, amount = 2, now = 1999))
        val expired = assertNotNull(CountersTable.get("quota", "a"))
        assertFalse(expired.isExpired(1999))
        assertTrue(expired.isExpired(2000))
        assertEquals(CounterTable.Counter(5, 2000, 3000), CountersTable.increment("quota", "a", 1000, amount = 5, now = 2000))
    }

    @Test
    fun `sliding expiry extends the window and resets after inactivity`() {
        CountersTable.increment("failures", "a", 1000, sliding = true, now = 1000)
        assertEquals(CounterTable.Counter(2, 1000, 2500), CountersTable.increment("failures", "a", 1000, sliding = true, now = 1500))
        assertEquals(CounterTable.Counter(1, 2500, 3500), CountersTable.increment("failures", "a", 1000, sliding = true, now = 2500))
    }

    @Test
    fun `totals do not expire and support increments beyond int range`() {
        val amount = Int.MAX_VALUE.toLong() + 1
        CountersTable.increment("bytes", "a", amount = amount, now = 0)
        assertEquals(CounterTable.Counter(amount + 7, 0, null), CountersTable.increment("bytes", "a", amount = 7, now = Long.MAX_VALUE))
        assertEquals(0, CountersTable.clearExpired(Long.MAX_VALUE))
        assertFalse(assertNotNull(CountersTable.get("bytes", "a")).isExpired(Long.MAX_VALUE))
    }

    @Test
    fun `scopes keys and custom tables remain independent`() {
        CountersTable.increment("a:b", "c")
        CountersTable.increment("a", "b:c", amount = 2)
        CountersTable.increment("a", "B:C", amount = 3)
        customTable.increment("a:b", "c", amount = 4)
        assertEquals(1, CountersTable.clear("a:b", "c"))
        assertEquals(0, CountersTable.clear("a:b", "c"))
        assertNull(CountersTable.get("a:b", "c"))
        assertEquals(2, CountersTable.get("a", "b:c")?.count)
        assertEquals(3, CountersTable.get("a", "B:C")?.count)
        assertEquals(4, customTable.get("a:b", "c")?.count)
    }

    @Test
    fun `cleanup removes only expired rows including the expiry boundary`() {
        CountersTable.increment("a", "expired", 100, now = 0)
        CountersTable.increment("b", "active", 101, now = 0)
        CountersTable.increment("b", "total", now = 0)
        assertEquals(1, CountersTable.clearExpired(100))
        assertNull(CountersTable.get("a", "expired"))
        assertNotNull(CountersTable.get("b", "active"))
        assertNotNull(CountersTable.get("b", "total"))
    }

    @Test
    fun `operations observe current writes and follow transaction rollback`() {
        assertFailsWith<IllegalStateException> {
            db {
                assertNull(CountersTable.get("a", "key"))
                CountersTable.increment("a", "key")
                assertEquals(1, CountersTable.get("a", "key")?.count)
                CountersTable.increment("a", "key")
                assertEquals(2, CountersTable.get("a", "key")?.count)
                error("rollback")
            }
        }
        assertNull(CountersTable.get("a", "key"))
    }

    @Test
    fun `concurrent creation and expired reset do not lose increments`() {
        fun incrementTogether(now: Long): List<Long> {
            val start = CountDownLatch(1)
            return Executors.newFixedThreadPool(8).use { executor ->
                val futures = (1..24).map {
                    executor.submit<Long> {
                        check(start.await(10, TimeUnit.SECONDS))
                        CountersTable.increment("concurrent", "key", 100, now = now).count
                    }
                }
                start.countDown()
                futures.map { it.get(30, TimeUnit.SECONDS) }.sorted()
            }
        }
        assertEquals((1L..24L).toList(), incrementTogether(0))
        assertEquals((1L..24L).toList(), incrementTogether(100))
        assertEquals(CounterTable.Counter(24, 100, 200), CountersTable.get("concurrent", "key"))
    }

    @Test
    fun `invalid counter arguments are rejected before mutation`() {
        assertFailsWith<IllegalArgumentException> { CountersTable.increment("", "key") }
        assertFailsWith<IllegalArgumentException> { CountersTable.increment("a".repeat(129), "key") }
        assertFailsWith<IllegalArgumentException> { CountersTable.increment("a", "") }
        assertFailsWith<IllegalArgumentException> { CountersTable.increment("a", "k".repeat(513)) }
        assertFailsWith<IllegalArgumentException> { CountersTable.increment("a", "key", amount = 0) }
        assertFailsWith<IllegalArgumentException> { CountersTable.increment("a", "key", windowMillis = 0) }
        assertFailsWith<IllegalArgumentException> { CountersTable.increment("a", "key", sliding = true) }
        assertFailsWith<ArithmeticException> { CountersTable.increment("a", "key", windowMillis = 1, now = Long.MAX_VALUE) }
        assertNull(CountersTable.get("a", "key"))
    }
}
