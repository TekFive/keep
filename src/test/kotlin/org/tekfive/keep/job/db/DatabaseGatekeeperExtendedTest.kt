package org.tekfive.keep.job.db

import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.system.measureTimeMillis
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseGatekeeperExtendedTest {

    private fun createGatekeeper(): DatabaseGatekeeper {
        return DatabaseGatekeeper(TestJobConfiguration())
    }

    // --- isRecoverable for class 25 (Invalid Transaction State) ---

    @Test
    fun `isRecoverable returns true for class 25 sqlState`() {
        val gk = createGatekeeper()
        assertTrue(gk.isRecoverable(SQLException("invalid transaction", "25000")))
    }

    @Test
    fun `isRecoverable returns true for class 25001 sqlState`() {
        val gk = createGatekeeper()
        assertTrue(gk.isRecoverable(SQLException("active sql transaction", "25001")))
    }

    // --- isRecoverable for class 40 (Transaction Rollback) ---

    @Test
    fun `isRecoverable returns true for class 40 sqlState`() {
        val gk = createGatekeeper()
        assertTrue(gk.isRecoverable(SQLException("transaction rollback", "40000")))
    }

    @Test
    fun `isRecoverable returns true for class 40001 serialization failure`() {
        val gk = createGatekeeper()
        assertTrue(gk.isRecoverable(SQLException("serialization failure", "40001")))
    }

    // --- isRecoverable for class 53 (Insufficient Resources) ---

    @Test
    fun `isRecoverable returns true for class 53 sqlState`() {
        val gk = createGatekeeper()
        assertTrue(gk.isRecoverable(SQLException("insufficient resources", "53000")))
    }

    @Test
    fun `isRecoverable returns true for class 53100 disk full`() {
        val gk = createGatekeeper()
        assertTrue(gk.isRecoverable(SQLException("disk full", "53100")))
    }

    @Test
    fun `isRecoverable returns true for class 53200 out of memory`() {
        val gk = createGatekeeper()
        assertTrue(gk.isRecoverable(SQLException("out of memory", "53200")))
    }

    @Test
    fun `isRecoverable returns true for class 53300 too many connections`() {
        val gk = createGatekeeper()
        assertTrue(gk.isRecoverable(SQLException("too many connections", "53300")))
    }

    // --- isRecoverable for class 57 (Operator Intervention) ---

    @Test
    fun `isRecoverable returns true for class 57 sqlState`() {
        val gk = createGatekeeper()
        assertTrue(gk.isRecoverable(SQLException("operator intervention", "57000")))
    }

    @Test
    fun `isRecoverable returns true for class 57014 query cancelled`() {
        val gk = createGatekeeper()
        assertTrue(gk.isRecoverable(SQLException("query cancelled", "57014")))
    }

    // --- isRecoverable for non-recoverable classes ---

    @Test
    fun `isRecoverable returns false for class 23 integrity violation`() {
        val gk = createGatekeeper()
        assertFalse(gk.isRecoverable(SQLException("unique violation", "23505")))
    }

    @Test
    fun `isRecoverable returns false for class 42 syntax error`() {
        val gk = createGatekeeper()
        assertFalse(gk.isRecoverable(SQLException("column not found", "42703")))
    }

    @Test
    fun `isRecoverable returns false for class 22 data exception`() {
        val gk = createGatekeeper()
        assertFalse(gk.isRecoverable(SQLException("division by zero", "22012")))
    }

    @Test
    fun `isRecoverable returns false for empty sqlState`() {
        val gk = createGatekeeper()
        assertFalse(gk.isRecoverable(SQLException("no state", "")))
    }

    // --- onSQLException ---

    @Test
    fun `onSQLException returns true for recoverable exception`() {
        val gk = createGatekeeper()
        val result = gk.onSQLException(SQLException("connection refused", "08001"))
        assertTrue(result)
    }

    @Test
    fun `onSQLException returns false for non-recoverable exception`() {
        val gk = createGatekeeper()
        val result = gk.onSQLException(SQLException("syntax error", "42000"))
        assertFalse(result)
    }

    @Test
    fun `onSQLException does not delay later work for non-recoverable exception`() {
        val gk = createGatekeeper()

        assertFalse(gk.onSQLException(SQLException("syntax error", "42000")))

        val elapsed = measureTimeMillis {
            gk { }
        }
        assertTrue(elapsed < 250, "Non-recoverable exceptions should not arm database backoff; elapsed=${elapsed}ms")
    }
}
