package org.tekfive.keep.utils

import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.tekfive.keep.data.TestDatabase
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseExceptionsIntegrationTest {
    @Test
    fun `terminated postgres connection is recoverable through Exposed wrapper`() {
        val database = TestDatabase.connect()
        val failure = assertFailsWith<ExposedSQLException> {
            transaction(database) {
                maxAttempts = 1
                exec("SELECT pg_terminate_backend(pg_backend_pid())")
            }
        }
        assertTrue(failure.isDatabaseException())
        assertTrue(failure.isRecoverableDatabaseException())
        assertTrue(IllegalStateException(failure).isRecoverableDatabaseException())
    }

    @Test
    fun `real postgres data and syntax errors remain nonrecoverable through Exposed`() {
        val database = TestDatabase.connect()
        for (sql in listOf("SELECT 1 / 0", "SELECT 'invalid'::integer", "SELECT FROM")) {
            val failure = assertFailsWith<ExposedSQLException> {
                transaction(database) {
                    maxAttempts = 1
                    exec(sql)
                }
            }
            assertTrue(failure.isDatabaseException())
            assertFalse(failure.isRecoverableDatabaseException())
            assertFalse(IllegalStateException(failure).isRecoverableDatabaseException())
        }
    }
}
