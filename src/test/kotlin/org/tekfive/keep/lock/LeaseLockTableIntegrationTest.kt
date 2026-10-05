package org.tekfive.keep.lock

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.db.noDbCommit
import org.tekfive.keep.migration.dynamic.PostgresMigrationGenerator
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresFreshInstallGenerator
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class LeaseLockTableIntegrationTest {
    private lateinit var database: Database
    private lateinit var locks: LeaseLockTable

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        locks = LeaseLockTable("keep_lease_locks_test", connectionProvider = ::connect)
        transaction(database) { SchemaUtils.create(locks) }
    }

    @AfterTest
    fun cleanup() {
        transaction(database) { SchemaUtils.drop(locks) }
    }

    private fun connect(): Connection = database.connector().connection as Connection

    @Test
    fun `leases are exclusive per case sensitive key and generations survive release`() {
        val key = "sync:tenant's ? 日本語"
        val first = assertNotNull(locks.tryAcquire(key, 30.seconds))
        assertEquals(1, first.fencingToken)
        assertNull(locks.tryAcquire(key, 30.seconds))
        assertNotNull(locks.tryAcquire(key.uppercase(), 30.seconds)).close()
        assertTrue(first.release())
        assertFalse(first.release())
        assertFailsWith<LeaseLostException> { first.checkIn() }

        assertNotNull(locks.tryAcquire(key, 30.seconds)).use { second ->
            assertEquals(2, second.fencingToken)
            assertNotEquals(first.ownerToken, second.ownerToken)
            first.close()
            assertNull(locks.tryAcquire(key, 30.seconds))
        }
        assertNotNull(locks.tryAcquire(key, 30.seconds)).use { assertEquals(3, it.fencingToken) }
    }

    @Test
    fun `concurrent first acquisition has one winner`() {
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(8).use { executor ->
            val futures = (1..8).map {
                executor.submit<LockLease?> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    locks.tryAcquire("race", 30.seconds)
                }
            }
            start.countDown()
            val winners = futures.mapNotNull { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, winners.size)
            winners.single().close()
        }
    }

    @Test
    fun `checkin renews from database time and preserves acquisition identity`() {
        assertNotNull(locks.tryAcquire("renew", 30.seconds)).use { lease ->
            val initial = lease.expiresAt
            transaction(database) { exec("SELECT pg_sleep(0.01)") }
            val renewed = lease.checkIn()
            assertTrue(renewed > initial)
            assertEquals(renewed, lease.expiresAt)
            transaction(database) {
                val row = locks.selectAll().where { locks.lockKey eq lease.key }.single()
                assertEquals(lease.acquiredAt, row[locks.acquiredAt])
                assertEquals(lease.fencingToken, row[locks.fencingToken])
                assertEquals(lease.ownerToken, row[locks.ownerToken])
                assertEquals(renewed, row[locks.lastCheckInAt]!!.plusMillis(30_000))
                assertEquals(renewed, row[locks.expiresAt])
            }
        }
    }

    @Test
    fun `missed checkin permits takeover without a sweeper or release`() {
        val first = assertNotNull(locks.tryAcquire("crashed", 50.milliseconds))
        awaitDatabaseExpiry(first)
        assertFailsWith<LeaseLostException> { first.checkIn() }
        assertNotNull(locks.tryAcquire("crashed", 30.seconds)).use { second ->
            assertEquals(first.fencingToken + 1, second.fencingToken)
            assertFailsWith<LeaseLostException> { first.checkIn() }
            assertFalse(first.release())
            second.checkIn()
            assertNull(locks.tryAcquire("crashed", 30.seconds))
        }
    }

    @Test
    fun `expired checkin racing takeover cannot resurrect previous ownership`() {
        val old = assertNotNull(locks.tryAcquire("takeover", 30.seconds))
        expire(old)
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val renew = executor.submit {
                start.await()
                assertFailsWith<LeaseLostException> { old.checkIn() }
            }
            val takeover = executor.submit<LockLease?> {
                start.await()
                locks.tryAcquire("takeover", 30.seconds)
            }
            start.countDown()
            renew.get(5, TimeUnit.SECONDS)
            assertNotNull(takeover.get(5, TimeUnit.SECONDS)).use {
                assertEquals(old.fencingToken + 1, it.fencingToken)
                assertFalse(old.release())
            }
        }
    }

    @Test
    fun `maximum lifetime caps initial expiry and every renewal`() {
        val lease = assertNotNull(locks.tryAcquire("deadline", 30.seconds, maxLifetime = 5.seconds))
        assertEquals(lease.acquiredAt.plusSeconds(5), lease.deadlineAt)
        assertEquals(lease.deadlineAt, lease.expiresAt)
        assertEquals(lease.deadlineAt, lease.checkIn())
        expire(lease)
        assertFailsWith<LeaseLostException> { lease.checkIn() }
        assertNotNull(locks.tryAcquire("deadline", 30.seconds)).close()
    }

    @Test
    fun `checkins are visible inside noDbCommit and survive outer rollback`() {
        lateinit var lease: LockLease
        transaction(database) {
            exec("CREATE TEMP TABLE lease_caller_work (id INTEGER)")
            noDbCommit {
                lease = assertNotNull(locks.tryAcquire("independent", 30.seconds))
                exec("INSERT INTO lease_caller_work VALUES (1)")
                lease.checkIn()
                // A second independent connection sees the committed ownership immediately.
                assertNull(locks.tryAcquire("independent", 30.seconds))
            }
            rollback()
        }
        assertNull(locks.tryAcquire("independent", 30.seconds))
        lease.checkIn()
        transaction(database) {
            noDbCommit { assertTrue(lease.release()) }
            rollback()
        }
        assertNotNull(locks.tryAcquire("independent", 30.seconds)).close()
    }

    @Test
    fun `waiting acquisition observes release and times out when still owned`() {
        val first = assertNotNull(locks.tryAcquire("wait", 30.seconds))
        assertFailsWith<LockAcquireTimeoutException> { locks.acquire("wait", 30.seconds, 80.milliseconds) }
        val opened = CountDownLatch(1)
        val waiter = LeaseLockTable(locks.tableName, connectionProvider = {
            connect().also { opened.countDown() }
        })
        Executors.newSingleThreadExecutor().use { executor ->
            val result = executor.submit<LockLease> { waiter.acquire("wait", 30.seconds, 5.seconds) }
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            first.close()
            result.get(5, TimeUnit.SECONDS).use { assertEquals(2, it.fencingToken) }
        }
    }

    @Test
    fun `row lock contention is bounded and returns no lease`() {
        assertNotNull(locks.tryAcquire("busy", 30.seconds)).close()
        val impatient = LeaseLockTable(locks.tableName, 200.milliseconds, ::connect)
        transaction(database) {
            exec("SELECT lock_key FROM ${locks.tableName} WHERE lock_key = 'busy' FOR UPDATE")
            val started = TimeSource.Monotonic.markNow()
            assertNull(impatient.tryAcquire("busy", 30.seconds))
            assertTrue(started.elapsedNow() < 3.seconds)
        }
        assertNotNull(impatient.tryAcquire("busy", 30.seconds)).close()
    }

    @Test
    fun `renewal samples time after row lock wait and cannot revive an expired lease`() {
        val old = assertNotNull(locks.tryAcquire("blocked-renewal", 200.milliseconds))
        val opened = CountDownLatch(1)
        val waitingTable = LeaseLockTable(locks.tableName, connectionProvider = {
            connect().also { opened.countDown() }
        })
        // Another handle to the same acquisition lets the test observe the connection attempt.
        val handle = LockLease(waitingTable, old.key, old.ownerToken, old.fencingToken,
            old.leaseFor, old.acquiredAt, old.expiresAt, old.deadlineAt)
        Executors.newSingleThreadExecutor().use { executor ->
            lateinit var result: java.util.concurrent.Future<*>
            transaction(database) {
                exec("SELECT lock_key FROM ${locks.tableName} WHERE lock_key = 'blocked-renewal' FOR UPDATE")
                result = executor.submit { assertFailsWith<LeaseLostException> { handle.checkIn() } }
                assertTrue(opened.await(5, TimeUnit.SECONDS))
                // Hold the lock past expiry without changing the row. Renewal must sample time
                // after this transaction commits, rather than use its earlier statement time.
                exec("SELECT pg_sleep(0.3)")
            }
            result.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `lease handles hold no connections and error paths close their connections`() {
        val open = AtomicInteger()
        val tracked = LeaseLockTable(locks.tableName, connectionProvider = {
            val connection = connect()
            open.incrementAndGet()
            object : Connection by connection {
                override fun close() {
                    try {
                        connection.close()
                    } finally {
                        open.decrementAndGet()
                    }
                }
            }
        })
        val lease = assertNotNull(tracked.tryAcquire("connections", 30.seconds))
        assertEquals(0, open.get())
        assertNull(tracked.tryAcquire("connections", 30.seconds))
        assertEquals(0, open.get())
        lease.checkIn()
        assertEquals(0, open.get())
        expire(lease)
        assertFailsWith<LeaseLostException> { lease.checkIn() }
        assertEquals(0, open.get())
        lease.close()
        assertEquals(0, open.get())
    }

    @Test
    fun `live renewal and competing acquisition preserve the current owner`() {
        val lease = assertNotNull(locks.tryAcquire("live-race", 30.seconds))
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val checkIn = executor.submit { start.await(); lease.checkIn() }
            val acquire = executor.submit<LockLease?> { start.await(); locks.tryAcquire("live-race", 30.seconds) }
            start.countDown()
            checkIn.get(5, TimeUnit.SECONDS)
            assertNull(acquire.get(5, TimeUnit.SECONDS))
        }
        assertTrue(lease.release())
    }

    @Test
    fun `database errors propagate and failed checkins do not update local expiry`() {
        val unavailable = AtomicBoolean(false)
        val flaky = LeaseLockTable(locks.tableName, connectionProvider = {
            if (unavailable.get()) throw SQLException("unavailable", "08006")
            connect()
        })
        val lease = assertNotNull(flaky.tryAcquire("failure", 30.seconds))
        val expires = lease.expiresAt
        unavailable.set(true)
        assertFailsWith<SQLException> { flaky.tryAcquire("another", 30.seconds) }
        assertFailsWith<SQLException> { lease.checkIn() }
        assertEquals(expires, lease.expiresAt)
        unavailable.set(false)
        lease.checkIn()
        lease.close()
        val missing = LeaseLockTable("missing_lease_table", connectionProvider = ::connect)
        assertEquals("42P01", assertFailsWith<SQLException> { missing.tryAcquire("a", 30.seconds) }.sqlState)
    }

    @Test
    fun `use releases on failure and interruption opens no connection`() {
        assertFailsWith<IllegalArgumentException> {
            assertNotNull(locks.tryAcquire("use", 30.seconds)).use { throw IllegalArgumentException("work failed") }
        }
        assertNotNull(locks.tryAcquire("use", 30.seconds)).close()
        val connections = AtomicInteger()
        val guarded = LeaseLockTable(locks.tableName, connectionProvider = {
            connections.incrementAndGet()
            connect()
        })
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<InterruptedException> { guarded.acquire("interrupt", 30.seconds, 1.seconds) }
        } finally {
            Thread.interrupted()
        }
        assertEquals(0, connections.get())
    }

    @Test
    fun `invalid inputs are rejected before connecting`() {
        val unused = LeaseLockTable("unused", connectionProvider = { error("must not connect") })
        for (duration in listOf(Duration.ZERO, (-1).seconds, Duration.INFINITE, 0.5.milliseconds)) {
            assertFailsWith<IllegalArgumentException> { unused.tryAcquire("a", duration) }
            assertFailsWith<IllegalArgumentException> { unused.tryAcquire("a", 1.seconds, duration) }
            assertFailsWith<IllegalArgumentException> { unused.acquire("a", 1.seconds, duration) }
            assertFailsWith<IllegalArgumentException> { LeaseLockTable("unused", duration) }
        }
        assertFailsWith<IllegalArgumentException> { unused.tryAcquire(" ", 1.seconds) }
        assertFailsWith<IllegalArgumentException> { unused.tryAcquire("a".repeat(513), 1.seconds) }
    }

    @Test
    fun `qualified lease tables work with fresh installation and dynamic migration`() {
        val schemaName = "keep_lease_schema"
        val qualified = LeaseLockTable("$schemaName.leases", connectionProvider = ::connect)
        val schema = object : KeepSchema(schemaName) { override val tables = listOf(qualified) }
        try {
            transaction(database) {
                PostgresFreshInstallGenerator.plan(schema).statements.forEach { exec(it) }
            }
            assertNotNull(qualified.tryAcquire("qualified", 30.seconds)).use { it.checkIn() }
            assertTrue(PostgresMigrationGenerator.plan(database, schema, nonDestructive = true).isEmpty)
            transaction(database) { exec("DROP SCHEMA $schemaName CASCADE") }
            PostgresMigrationGenerator.plan(database, schema, nonDestructive = true).execute(database)
            assertNotNull(qualified.tryAcquire("qualified", 30.seconds)).use { it.checkIn() }
            assertTrue(PostgresMigrationGenerator.plan(database, schema, nonDestructive = true).isEmpty)
        } finally {
            transaction(database) { exec("DROP SCHEMA IF EXISTS $schemaName CASCADE") }
        }
    }

    private fun expire(lease: LockLease) = transaction(database) {
        // Deliberate test-only mutation. Production callers must not modify ownership rows.
        exec("UPDATE ${locks.tableName} SET expires_at = 0 WHERE owner_token = '${lease.ownerToken}'")
    }

    private fun awaitDatabaseExpiry(lease: LockLease) {
        val started = TimeSource.Monotonic.markNow()
        while (transaction(database) {
            exec("SELECT clock_timestamp()") { result -> result.next(); result.getTimestamp(1).toInstant() }!!
        } < lease.expiresAt) {
            check(started.elapsedNow() < 5.seconds) { "Lease did not expire" }
            Thread.sleep(5)
        }
    }
}
