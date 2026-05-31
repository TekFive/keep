package org.tekfive.keep.job.db

import org.junit.jupiter.api.Test
import org.tekfive.keep.job.JobConfiguration
import org.tekfive.keep.job.db.JobRecordLogLevel
import java.sql.SQLException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseGatekeeperTest {

    private fun createGatekeeper(): DatabaseGatekeeper {
        return DatabaseGatekeeper(TestJobConfiguration())
    }

    @Test
    fun `isRecoverable returns true for connection exception`() {
        val gk = createGatekeeper()
        assertTrue(gk.isRecoverable(SQLException("connection refused", "08001")))
    }

    @Test
    fun `isRecoverable returns false for syntax error`() {
        val gk = createGatekeeper()
        assertFalse(gk.isRecoverable(SQLException("syntax error", "42000")))
    }

    @Test
    fun `isRecoverable returns true for class 08 sqlState`() {
        val gk = createGatekeeper()
        assertTrue(gk.isRecoverable(SQLException("conn", "08006")))
    }

    @Test
    fun `isRecoverable returns false when sqlState is null`() {
        val gk = createGatekeeper()
        assertFalse(gk.isRecoverable(SQLException("no state", null as String?)))
    }
}

internal class TestJobConfiguration : JobConfiguration {
    override val dispatchCount: Int = 1
    override val pollSeconds: Int = 30
    override val maximumCandidatesBuffer: Int = 2
    override val maxEstimatedRuntimeRecords: Int = 10
    override val minSecondsBetweenJobCheckin: Int = 30
    override val defaultMinSecondsBetweenJobRetry: Int = 300
    override val minSaveLogLevel: JobRecordLogLevel? = null
    override fun getDatabaseBackoffSeconds(e: SQLException): Int = 1
}
