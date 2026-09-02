package org.tekfive.keep.job

import org.junit.jupiter.api.Test
import org.tekfive.keep.job.db.DatabaseGatekeeper
import org.tekfive.keep.job.db.JobRecordLogLevel
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseGatekeeperRecoverabilityTest {

    private val gatekeeper = DatabaseGatekeeper(object : JobConfiguration {
        override val dispatchCount: Int = 1
        override val pollSeconds: Int = 1
        override val maximumCandidatesBuffer: Int = 1
        override val maxEstimatedRuntimeRecords: Int = 0
        override val minSecondsBetweenJobCheckin: Int = 1
        override val defaultMinSecondsBetweenJobRetry: Int = 0
        override val minSaveLogLevel: JobRecordLogLevel? = null
        override fun getDatabaseBackoffSeconds(e: SQLException): Int = 1
    })

    @Test
    fun `pool timeout without a sql state is recoverable`() {
        assertTrue(gatekeeper.isRecoverable(SQLTransientConnectionException("pool - Connection is not available")))
    }

    @Test
    fun `recoverable and non-transient connection exceptions are recoverable`() {
        assertTrue(gatekeeper.isRecoverable(SQLRecoverableException("link failure")))
        assertTrue(gatekeeper.isRecoverable(SQLNonTransientConnectionException("connection closed")))
    }

    @Test
    fun `wrapper without a sql state defers to its cause`() {
        val wrapped = SQLException(SQLException("connection refused", "08001"))
        assertTrue(gatekeeper.isRecoverable(wrapped))

        val wrappedSyntax = SQLException(SQLException("syntax", "42601"))
        assertFalse(gatekeeper.isRecoverable(wrappedSyntax))
    }

    @Test
    fun `plain exception without state or cause is not recoverable`() {
        assertFalse(gatekeeper.isRecoverable(SQLException("no state", null as String?)))
    }
}
