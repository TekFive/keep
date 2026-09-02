package org.tekfive.keep.job

import org.junit.jupiter.api.Test
import org.tekfive.keep.job.db.JobRecordLogLevel
import java.sql.SQLException
import java.time.Instant
import java.time.InstantSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobRecordConfigurationGuardTest {

    private fun createConfig(
        dispatchCount: Int = 4,
        pollSeconds: Int = 30,
        maximumCandidatesBuffer: Int = 8,
        saveFailureDetails: Boolean = true,
        maxEstimatedRuntimeRecords: Int = 10,
        minSecondsBetweenJobCheckin: Int = 30,
    ): JobConfiguration {
        return object : JobConfiguration {
            override val dispatchCount: Int = dispatchCount
            override val pollSeconds: Int = pollSeconds
            override val maximumCandidatesBuffer: Int = maximumCandidatesBuffer
            override val maxEstimatedRuntimeRecords: Int = maxEstimatedRuntimeRecords
            override val minSecondsBetweenJobCheckin: Int = minSecondsBetweenJobCheckin
            override val defaultMinSecondsBetweenJobRetry: Int = 300
            override val minSaveLogLevel: JobRecordLogLevel? = null
            override fun getDatabaseBackoffSeconds(e: SQLException): Int = 5
        }
    }

    // --- dispatchCount clamping ---

    @Test
    fun `dispatchCount passes through positive value`() {
        val guard = JobConfigurationGuard(createConfig(dispatchCount = 4))
        assertEquals(4, guard.dispatchCount)
    }

    @Test
    fun `dispatchCount clamps negative to 0`() {
        val guard = JobConfigurationGuard(createConfig(dispatchCount = -5))
        assertEquals(0, guard.dispatchCount)
    }

    @Test
    fun `dispatchCount allows 0`() {
        val guard = JobConfigurationGuard(createConfig(dispatchCount = 0))
        assertEquals(0, guard.dispatchCount)
    }

    // --- pollSeconds clamping ---

    @Test
    fun `pollSeconds passes through value above 1`() {
        val guard = JobConfigurationGuard(createConfig(pollSeconds = 30))
        assertEquals(30, guard.pollSeconds)
    }

    @Test
    fun `pollSeconds clamps 0 to 1`() {
        val guard = JobConfigurationGuard(createConfig(pollSeconds = 0))
        assertEquals(1, guard.pollSeconds)
    }

    @Test
    fun `pollSeconds clamps negative to 1`() {
        val guard = JobConfigurationGuard(createConfig(pollSeconds = -10))
        assertEquals(1, guard.pollSeconds)
    }

    @Test
    fun `pollSeconds allows exactly 1`() {
        val guard = JobConfigurationGuard(createConfig(pollSeconds = 1))
        assertEquals(1, guard.pollSeconds)
    }

    // --- maximumCandidatesBuffer clamping ---

    @Test
    fun `maximumCandidatesBuffer passes through positive value`() {
        val guard = JobConfigurationGuard(createConfig(maximumCandidatesBuffer = 10))
        assertEquals(10, guard.maximumCandidatesBuffer)
    }

    @Test
    fun `maximumCandidatesBuffer clamps negative to 0`() {
        val guard = JobConfigurationGuard(createConfig(maximumCandidatesBuffer = -3))
        assertEquals(0, guard.maximumCandidatesBuffer)
    }

    @Test
    fun `maximumCandidatesBuffer allows 0`() {
        val guard = JobConfigurationGuard(createConfig(maximumCandidatesBuffer = 0))
        assertEquals(0, guard.maximumCandidatesBuffer)
    }

    // --- maxEstimatedRuntimeRecords clamping ---

    @Test
    fun `maxEstimatedRuntimeRecords passes through positive value`() {
        val guard = JobConfigurationGuard(createConfig(maxEstimatedRuntimeRecords = 10))
        assertEquals(10, guard.maxEstimatedRuntimeRecords)
    }

    @Test
    fun `maxEstimatedRuntimeRecords clamps negative to 0`() {
        val guard = JobConfigurationGuard(createConfig(maxEstimatedRuntimeRecords = -1))
        assertEquals(0, guard.maxEstimatedRuntimeRecords)
    }

    @Test
    fun `maxEstimatedRuntimeRecords allows 0`() {
        val guard = JobConfigurationGuard(createConfig(maxEstimatedRuntimeRecords = 0))
        assertEquals(0, guard.maxEstimatedRuntimeRecords)
    }

    // --- minSecondsBetweenJobCheckin ---

    @Test
    fun `minSecondsBetweenJobCheckin clamps to at least 1`() {
        val guard = JobConfigurationGuard(createConfig(minSecondsBetweenJobCheckin = 0))
        assertEquals(1, guard.minSecondsBetweenJobCheckin)
    }

    @Test
    fun `minSecondsBetweenJobCheckin passes through positive value`() {
        val guard = JobConfigurationGuard(createConfig(minSecondsBetweenJobCheckin = 15))
        assertEquals(15, guard.minSecondsBetweenJobCheckin)
    }

    // --- saveFailureDetails pass-through ---

    // --- getDatabaseBackoffSeconds delegation ---

    @Test
    fun `getDatabaseBackoffSeconds delegates to underlying configuration`() {
        val config = object : JobConfiguration {
            override val dispatchCount: Int = 1
            override val pollSeconds: Int = 1
            override val maximumCandidatesBuffer: Int = 1
            override val maxEstimatedRuntimeRecords: Int = 10
            override val minSecondsBetweenJobCheckin: Int = 30
            override val defaultMinSecondsBetweenJobRetry: Int = 300
            override val minSaveLogLevel: JobRecordLogLevel? = null
            override fun getDatabaseBackoffSeconds(e: SQLException): Int = 42
        }
        val guard = JobConfigurationGuard(config)
        assertEquals(42, guard.getDatabaseBackoffSeconds(SQLException("test")))
    }

    // --- reclaimOrphanedJobsOnStart delegation ---

    @Test
    fun `reclaimOrphanedJobsOnStart defaults to true and delegates to underlying configuration`() {
        assertTrue(JobConfigurationGuard(createConfig()).reclaimOrphanedJobsOnStart)

        val config = object : JobConfiguration {
            override val dispatchCount: Int = 1
            override val pollSeconds: Int = 1
            override val maximumCandidatesBuffer: Int = 1
            override val maxEstimatedRuntimeRecords: Int = 10
            override val minSecondsBetweenJobCheckin: Int = 30
            override val defaultMinSecondsBetweenJobRetry: Int = 300
            override val minSaveLogLevel: JobRecordLogLevel? = null
            override val reclaimOrphanedJobsOnStart: Boolean = false
            override fun getDatabaseBackoffSeconds(e: SQLException): Int = 5
        }
        assertFalse(JobConfigurationGuard(config).reclaimOrphanedJobsOnStart)
    }
}
