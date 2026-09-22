package org.tekfive.keep.lock

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.db.db
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransactionLocksTest {
    private lateinit var database: Database

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
    }

    @Test
    fun `both methods require an active transaction`() {
        assertFailsWith<IllegalStateException> { TransactionLocks.tryAcquire("outside") }
        assertFailsWith<IllegalStateException> { TransactionLocks.acquire("outside") }
    }

    @Test
    fun `tryAcquire is reentrant and only contends for the same key`() {
        val key = "audit:tenant's chain ? 日本語"
        Executors.newSingleThreadExecutor().use { executor ->
            db {
                assertTrue(TransactionLocks.tryAcquire(key))
                assertTrue(TransactionLocks.tryAcquire(key))
                TransactionLocks.acquire(key)

                executor.submit {
                    transaction(database) {
                        // A failed try must not abort the transaction or consume its lock wait budget.
                        exec("SET LOCAL lock_timeout = '100ms'")
                        assertFalse(TransactionLocks.tryAcquire(key))
                        assertFalse(TransactionLocks.tryAcquire(key))
                        assertTrue(TransactionLocks.tryAcquire("another-chain"))
                    }
                }.get(5, TimeUnit.SECONDS)
            }
            assertTrue(executor.submit<Boolean> {
                transaction(database) { TransactionLocks.tryAcquire(key) }
            }.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `rollback releases a blocking acquired lock`() {
        val key = "rollback-chain"
        Executors.newSingleThreadExecutor().use { executor ->
            assertFailsWith<IllegalArgumentException> {
                transaction(database) {
                    TransactionLocks.acquire(key)
                    assertFalse(executor.submit<Boolean> {
                        transaction(database) { TransactionLocks.tryAcquire(key) }
                    }.get(5, TimeUnit.SECONDS))
                    throw IllegalArgumentException("rollback")
                }
            }
            assertTrue(executor.submit<Boolean> {
                transaction(database) { TransactionLocks.tryAcquire(key) }
            }.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `acquire waits for the owner to commit then obtains the lock`() {
        val key = "contended-chain"
        val waiterPid = AtomicInteger()
        val waiterStarted = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { executor ->
            lateinit var acquired: Future<Boolean>
            transaction(database) {
                TransactionLocks.acquire(key)
                acquired = executor.submit<Boolean> {
                    transaction(database) {
                        maxAttempts = 1
                        exec("SET LOCAL lock_timeout = '10s'")
                        waiterPid.set(exec("SELECT pg_backend_pid()") { result ->
                            check(result.next())
                            result.getInt(1)
                        }!!)
                        waiterStarted.countDown()
                        TransactionLocks.acquire(key)
                        TransactionLocks.tryAcquire(key)
                    }
                }
                assertTrue(waiterStarted.await(5, TimeUnit.SECONDS))

                // Observe a real database lock wait before allowing the owning transaction to commit.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var waiting = false
                while (!waiting && System.nanoTime() < deadline) {
                    waiting = exec(
                        "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ${waiterPid.get()} " +
                            "AND locktype = 'advisory' AND NOT granted)",
                    ) { result ->
                        check(result.next())
                        result.getBoolean(1)
                    } == true
                    if (!waiting) Thread.sleep(10)
                }
                assertTrue(waiting, "The competing transaction should wait for the advisory lock")
                assertFalse(acquired.isDone)
            }
            assertTrue(acquired.get(5, TimeUnit.SECONDS))
        }
    }
}
