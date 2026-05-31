package org.tekfive.keep.migration

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.db.db
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private object ConcTbl : Table("mr_conc_test_t") {
    val id = long("id")
    override val primaryKey = PrimaryKey(id)
}

class MigrationConcurrencyTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
    }

    @AfterTest
    fun teardown() {
        runCatching { transaction { SchemaUtils.drop(MigrationHistoryTable, ConcTbl) } }
    }

    @Test
    fun `two concurrent runners apply each migration at most once`() {
        val applyCount = AtomicInteger(0)
        val gate = CountDownLatch(1)

        // The migration blocks at the start of apply() until the gate
        // releases, so both runners are guaranteed to be racing on the
        // advisory lock when the gate opens.
        val migration = object : Migration {
            override val version = 1L
            override val name = "create_conc_t"
            override fun apply(tx: JdbcTransaction) {
                gate.await(5, TimeUnit.SECONDS)
                applyCount.incrementAndGet()
                SchemaUtils.create(ConcTbl)
            }
        }

        val pool = Executors.newFixedThreadPool(2)
        try {
            val f1 = pool.submit { MigrationRunner.run(listOf(migration)) }
            val f2 = pool.submit { MigrationRunner.run(listOf(migration)) }

            // Give both threads a moment to enter their per-migration
            // transactions and contend for the advisory lock.
            Thread.sleep(200)
            gate.countDown()

            f1.get(15, TimeUnit.SECONDS)
            f2.get(15, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, applyCount.get(), "Migration apply() must execute exactly once across both peers")

        db {
            val historyRows = MigrationHistoryTable.selectAll().toList()
            assertEquals(1, historyRows.size, "History must contain exactly one row for V1")
            assertTrue(
                SchemaUtils.listTables().any { it.endsWith(ConcTbl.tableName, ignoreCase = true) },
                "ConcTbl must exist after race"
            )
        }
    }

    @Test
    fun `failed migration releases advisory lock so subsequent runs proceed`() {
        // First run fails inside apply(). Without the finally-release in
        // MigrationRunner, the underlying connection returns to HikariCP
        // holding the advisory lock, and the SECOND run blocks forever
        // when it tries to acquire the same lock.
        val failingMigration = object : Migration {
            override val version = 1L
            override val name = "fails_first"
            override fun apply(tx: JdbcTransaction) {
                error("intentional failure")
            }
        }
        val successMigration = object : Migration {
            override val version = 1L
            override val name = "succeeds_second"
            override fun apply(tx: JdbcTransaction) {
                SchemaUtils.create(ConcTbl)
            }
        }

        // First attempt must throw (the migration's error propagates).
        kotlin.runCatching { MigrationRunner.run(listOf(failingMigration)) }
            .onSuccess { error("First run should have thrown") }

        // Second attempt must complete promptly. We bound it on a timeout —
        // if the lock leaked, this future will time out at the bounded
        // wait below and we fail loudly rather than hang the test JVM.
        val pool = Executors.newSingleThreadExecutor()
        try {
            val f = pool.submit { MigrationRunner.run(listOf(successMigration)) }
            f.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        db {
            assertEquals(
                1,
                MigrationHistoryTable.selectAll().toList().size,
                "History must contain the successful retry's row"
            )
            assertTrue(
                SchemaUtils.listTables().any { it.endsWith(ConcTbl.tableName, ignoreCase = true) },
                "ConcTbl must be created by the successful retry"
            )
        }
    }

    @Test
    fun `sql failure clears aborted transaction before advisory lock release`() {
        val failingMigration = object : Migration {
            override val version = 1L
            override val name = "sql_fails_first"
            override fun apply(tx: JdbcTransaction) {
                tx.exec("SELECT * FROM definitely_missing_migration_table")
            }
        }
        val successMigration = object : Migration {
            override val version = 1L
            override val name = "succeeds_second"
            override fun apply(tx: JdbcTransaction) {
                SchemaUtils.create(ConcTbl)
            }
        }

        assertFailsWith<Exception> {
            MigrationRunner.run(listOf(failingMigration))
        }

        val pool = Executors.newSingleThreadExecutor()
        try {
            val f = pool.submit { MigrationRunner.run(listOf(successMigration)) }
            f.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        db {
            assertEquals(1, MigrationHistoryTable.selectAll().toList().size)
            assertTrue(
                SchemaUtils.listTables().any { it.endsWith(ConcTbl.tableName, ignoreCase = true) },
                "ConcTbl must be created by the successful retry"
            )
        }
    }
}
