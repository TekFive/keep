package org.tekfive.keep.utils

import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.sql.BatchUpdateException
import java.sql.SQLDataException
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLSyntaxErrorException
import java.sql.SQLTimeoutException
import java.sql.SQLTransactionRollbackException
import java.sql.SQLTransientConnectionException
import java.sql.SQLTransientException
import java.util.concurrent.CancellationException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseExceptionsTest {
    @Test
    fun `database detection follows wrappers without treating ordinary IO as database errors`() {
        assertTrue(SQLException().isDatabaseException())
        assertTrue(IllegalStateException(RuntimeException(SQLException())).isDatabaseException())
        assertFalse(IOException().isDatabaseException())
        assertFalse(IllegalArgumentException("database connection failed").isDatabaseException())
    }

    @Test
    fun `postgres connection and infrastructure states are recoverable`() {
        for (state in listOf(
            "08000", "08001", "08003", "08004", "08006", "08007", "08P01",
            "53000", "53100", "53200", "53300", "53400", "57P01", "57P02", "57P03", "57P05",
            "25P03", "25P04", "58030", "40001", "40P01", "55P03",
        )) {
            assertTrue(SQLException("failure", state).isRecoverableDatabaseException(), state)
        }
    }

    @Test
    fun `data constraints schema authentication and unknown states are not recoverable`() {
        for (state in listOf(
            "22001", "22012", "22P02", "23502", "23503", "23505", "23514", "23P01",
            "42601", "42501", "42P01", "42703", "28000", "28P01", "0A000", "3D000",
            "25000", "25001", "25P02", "40002", "40003", "57000", "57014", "57P04",
            "58P01", "XX001", "ZZ999",
        )) {
            assertFalse(SQLException("failure", state).isRecoverableDatabaseException(), state)
        }
    }

    @Test
    fun `jdbc types identify recoverable failures without sqlstate`() {
        for (exception in listOf(
            SQLTransientException(), SQLTransientConnectionException(), SQLTimeoutException(),
            SQLTransactionRollbackException(), SQLRecoverableException(), SQLNonTransientConnectionException(),
        )) {
            assertTrue(exception.isRecoverableDatabaseException(), exception.javaClass.name)
        }
    }

    @Test
    fun `jdbc permanent error types defeat IO causes without sqlstate`() {
        for (exception in listOf(
            SQLDataException(), SQLIntegrityConstraintViolationException(), SQLSyntaxErrorException(),
            SQLInvalidAuthorizationSpecException(), SQLFeatureNotSupportedException(),
        )) {
            exception.initCause(IOException())
            assertFalse(exception.isRecoverableDatabaseException(), exception.javaClass.name)
        }
    }

    @Test
    fun `IO must be caused by database work`() {
        for (io in listOf(IOException(), EOFException(), ConnectException(), SocketTimeoutException())) {
            assertFalse(io.isRecoverableDatabaseException())
            assertFalse(RuntimeException(io).isRecoverableDatabaseException())
            assertTrue(RuntimeException(SQLException(RuntimeException(io))).isRecoverableDatabaseException())
        }
        // An I/O wrapper outside the JDBC failure says nothing about the database failure itself.
        assertFalse(IOException(SQLException()).isRecoverableDatabaseException())
    }

    @Test
    fun `sqlstate takes precedence over types and causes`() {
        assertFalse(SQLTransientConnectionException("invalid credentials", "28P01").isRecoverableDatabaseException())
        assertFalse(SQLTimeoutException("cancelled", "57014").isRecoverableDatabaseException())
        assertFalse(SQLException("invalid data", "22000", IOException()).isRecoverableDatabaseException())
        assertFalse(SQLRecoverableException(SQLIntegrityConstraintViolationException()).isRecoverableDatabaseException())
    }

    @Test
    fun `missing and generic wrapper states defer to nested failures`() {
        for (state in listOf(null, "", " ", "HY000")) {
            assertTrue(SQLException("wrapper", state, SQLException("connection", "08006")).isRecoverableDatabaseException())
            assertTrue(SQLException("wrapper", state, IOException()).isRecoverableDatabaseException())
            assertFalse(SQLException("wrapper", state).isRecoverableDatabaseException())
            assertFalse(SQLException("wrapper", state, SQLException("data", "23505")).isRecoverableDatabaseException())
        }
    }

    @Test
    fun `batch next exceptions are inspected and permanent failures win in either order`() {
        val batch = BatchUpdateException().apply {
            setNextException(SQLException("connection", "08006"))
        }
        assertTrue(RuntimeException(batch).isRecoverableDatabaseException())
        batch.setNextException(SQLException("duplicate", "23505"))
        assertFalse(batch.isRecoverableDatabaseException())

        val duplicateFirst = BatchUpdateException().apply {
            setNextException(SQLException("duplicate", "23505"))
            setNextException(SQLException("connection", "08006"))
        }
        assertFalse(duplicateFirst.isRecoverableDatabaseException())
    }

    @Test
    fun `suppressed cleanup exceptions do not change the primary failure`() {
        val ordinary = IllegalArgumentException().apply { addSuppressed(SQLTransientConnectionException()) }
        assertFalse(ordinary.isDatabaseException())
        assertFalse(ordinary.isRecoverableDatabaseException())
        val recoverable = SQLTransientConnectionException().apply { addSuppressed(SQLDataException()) }
        assertTrue(recoverable.isRecoverableDatabaseException())
    }

    @Test
    fun `interruption and cancellation are not retry signals`() {
        assertFalse(SQLTransientException(InterruptedException()).isRecoverableDatabaseException())
        assertFalse(CancellationException().apply { initCause(SQLTransientException()) }.isRecoverableDatabaseException())
    }

    @Test
    fun `cycles in causes and JDBC chains terminate`() {
        val first = SQLException()
        val second = SQLException()
        first.initCause(second)
        second.initCause(first)
        first.setNextException(second)
        second.setNextException(first)
        assertTrue(first.isDatabaseException())
        assertFalse(first.isRecoverableDatabaseException())

        val ordinary = RuntimeException()
        val wrapper = RuntimeException(ordinary)
        ordinary.initCause(wrapper)
        assertFalse(ordinary.isDatabaseException())
        assertFalse(ordinary.isRecoverableDatabaseException())
    }
}
