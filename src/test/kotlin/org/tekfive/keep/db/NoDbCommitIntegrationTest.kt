package org.tekfive.keep.db

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NoDbCommitIntegrationTest {
    private lateinit var database: Database

    @BeforeTest
    fun setup() {
        val source = TestDatabase.connect()
        database = Database.connect({ source.connector().connection as Connection }, DatabaseConfig {
            useNestedTransactions = true
            defaultMaxAttempts = 1
        })
    }

    @Test
    fun `requires an active transaction without running the block`() {
        var called = false
        assertFailsWith<IllegalStateException> { noDbCommit { called = true } }
        assertFalse(called)
        assertFalse(dbCommit())
        dbCommitQuietly()
    }

    @Test
    fun `rejects KEEP and Exposed commits and allows an eventual outer commit`() = withTable { table ->
        db(cache = false) {
            val tx = TransactionManager.current()
            val result = noDbCommit {
                tx.exec("INSERT INTO $table VALUES (1)")
                assertFailsWith<IllegalStateException> { dbCommit() }
                assertFailsWith<IllegalStateException> { dbCommitQuietly() }
                assertFailsWith<IllegalStateException> { tx.commit() }
                db(cache = false) { assertFailsWith<IllegalStateException> { dbCommit() } }
                "result"
            }
            assertEquals("result", result)
        }
        transaction(database) { assertEquals(1, rowCount(table)) }
    }

    @Test
    fun `rejected commits do not persist writes when the caller rolls back`() = withTable { table ->
        transaction(database) {
            val retainedConnection = dbConnection()
            noDbCommit {
                exec("INSERT INTO $table VALUES (1)")
                assertFailsWith<IllegalStateException> { retainedConnection.commit() }
                assertFailsWith<IllegalStateException> { dbConnection().commit() }
                assertFailsWith<IllegalStateException> { retainedConnection.autoCommit = true }
                assertFalse(retainedConnection.autoCommit)
                assertFailsWith<IllegalStateException> { commit() }
            }
            rollback()
        }
        transaction(database) { assertEquals(0, rowCount(table)) }
    }

    @Test
    fun `uncaught commit attempt rolls back the enclosing transaction`() = withTable { table ->
        assertFailsWith<IllegalStateException> {
            transaction(database) {
                noDbCommit {
                    exec("INSERT INTO $table VALUES (1)")
                    dbCommitQuietly()
                }
            }
        }
        transaction(database) {
            assertEquals(0, rowCount(table))
            assertTrue(dbCommit())
        }
    }

    @Test
    fun `nested scopes remain guarded after an inner exception`() = transaction(database) {
        noDbCommit {
            assertFailsWith<IllegalArgumentException> {
                noDbCommit { throw IllegalArgumentException("inner failure") }
            }
            assertFailsWith<IllegalStateException> { commit() }
            assertFailsWith<IllegalStateException> { dbConnection().commit() }
            noDbCommit { assertFailsWith<IllegalStateException> { dbCommit() } }
            assertFailsWith<IllegalStateException> { dbCommit() }
        }
        assertTrue(dbCommit())
        dbConnection().commit()
    }

    @Test
    fun `scope exception propagates and restores commit behavior`() = transaction(database) {
        val failure = IllegalArgumentException("original failure")
        assertSame(failure, assertFailsWith<IllegalArgumentException> { noDbCommit { throw failure } })
        assertTrue(dbCommit())
        dbConnection().commit()
    }

    @Test
    fun `rollback remains allowed without removing the guard`() = withTable { table ->
        transaction(database) {
            noDbCommit {
                exec("INSERT INTO $table VALUES (1)")
                assertTrue(org.tekfive.keep.db.rollback())
                assertEquals(0, rowCount(table))
                assertFailsWith<IllegalStateException> { commit() }
                assertFailsWith<IllegalStateException> { dbConnection().commit() }
                exec("INSERT INTO $table VALUES (2)")
            }
        }
        transaction(database) { assertEquals(1, rowCount(table)) }
    }

    @Test
    fun `scope in a savepoint transaction also guards its outer transaction`() {
        transaction(database) {
            val outer = this
            transaction(database) {
                noDbCommit {
                    assertFailsWith<IllegalStateException> { outer.commit() }
                    assertFailsWith<IllegalStateException> { commit() }
                    assertFailsWith<IllegalStateException> { dbConnection().commit() }
                }
            }
            outer.commit()
        }
    }

    @Test
    fun `nested savepoints can finish without committing the guarded connection`() = withTable { table ->
        transaction(database) {
            noDbCommit {
                db(cache = false, nestTransactions = true) {
                    TransactionManager.current().exec("INSERT INTO $table VALUES (1)")
                    assertFailsWith<IllegalStateException> { dbCommit() }
                    assertFailsWith<IllegalStateException> { dbConnection().commit() }
                }
                assertEquals(1, rowCount(table))
            }
            rollback()
        }
        transaction(database) { assertEquals(0, rowCount(table)) }
    }

    @Test
    fun `independent connections can commit while a scope is active`() {
        Executors.newSingleThreadExecutor().use { executor ->
            transaction(database) {
                noDbCommit {
                    assertTrue(executor.submit<Boolean> {
                        transaction(database) { dbCommit() }
                    }.get(5, TimeUnit.SECONDS))
                    assertFailsWith<IllegalStateException> { dbCommit() }
                }
            }
        }
    }

    @Test
    fun `managed connection wrapper guards direct Exposed connection commits`() {
        // Same connection wrapping used by DbConnection's pooled and unpooled providers.
        val managed = Database.connect({ CommitGuardConnection.wrap(database.connector().connection as Connection) })
        try {
            transaction(managed) {
                val jdbc = connection.connection as Connection
                noDbCommit {
                    assertFailsWith<IllegalStateException> { connection.commit() }
                    assertFailsWith<IllegalStateException> { jdbc.commit() }
                    assertFailsWith<IllegalStateException> { connection.autoCommit = true }
                    assertFailsWith<IllegalStateException> { jdbc.unwrap(Connection::class.java).commit() }
                }
                connection.commit()
            }
        } finally {
            TransactionManager.closeAndUnregister(managed)
        }
    }

    private fun withTable(block: (String) -> Unit) {
        val name = "no_commit_${UUID.randomUUID().toString().replace("-", "")}"
        transaction(database) { exec("CREATE TABLE $name (id INTEGER NOT NULL)") }
        try {
            block(name)
        } finally {
            transaction(database) { exec("DROP TABLE $name") }
        }
    }

    private fun JdbcTransaction.rowCount(table: String): Int = checkNotNull(
        exec("SELECT COUNT(*) FROM $table") { result ->
            check(result.next())
            result.getInt(1)
        },
    )
}
