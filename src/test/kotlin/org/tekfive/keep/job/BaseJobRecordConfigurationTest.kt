package org.tekfive.keep.job

import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BaseJobRecordConfigurationTest {

    @Test
    fun `database backoff policy returns seconds without converting them again`() {
        val config = DefaultJobConfiguration()

        assertEquals(2, config.getDatabaseBackoffSeconds(SQLException("connection lost", "08006")))
    }

    @Test
    fun `default dispatchCount is number of available processors`() {
        val config = DefaultJobConfiguration()
        assertEquals(Runtime.getRuntime().availableProcessors(), config.dispatchCount)
    }

    @Test
    fun `DEFAULT_DISPATCH_COUNT is number of available processors`() {
        assertEquals(Runtime.getRuntime().availableProcessors(), BaseJobConfiguration.DEFAULT_DISPATCH_COUNT)
    }

    @Test
    fun `default pollSeconds is 30`() {
        val config = DefaultJobConfiguration()
        assertEquals(30, config.pollSeconds)
    }

    @Test
    fun `DEFAULT_POLL_SECONDS is 30`() {
        assertEquals(30, BaseJobConfiguration.DEFAULT_POLL_SECONDS)
    }

    @Test
    fun `default maximumCandidatesBuffer is double dispatchCount`() {
        val config = DefaultJobConfiguration()
        assertEquals(config.dispatchCount * 2, config.maximumCandidatesBuffer)
    }

    @Test
    fun `default maxEstimatedRuntimeRecords is 10`() {
        val config = DefaultJobConfiguration()
        assertEquals(10, config.maxEstimatedRuntimeRecords)
    }

    @Test
    fun `DEFAULT_MAX_ESTIMATED_RUNTIME_RECORDS is 10`() {
        assertEquals(10, BaseJobConfiguration.DEFAULT_MAX_ESTIMATED_RUNTIME_RECORDS)
    }

    @Test
    fun `default minSecondsBetweenJobCheckin is 30`() {
        val config = DefaultJobConfiguration()
        assertEquals(30, config.minSecondsBetweenJobCheckin)
    }

    @Test
    fun `DEFAULT_MIN_SECONDS_BETWEEN_JOB_CHECKIN is 30`() {
        assertEquals(30, BaseJobConfiguration.DEFAULT_MIN_SECONDS_BETWEEN_JOB_CHECKIN)
    }
}
