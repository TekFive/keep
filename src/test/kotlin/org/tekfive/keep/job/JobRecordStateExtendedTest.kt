package org.tekfive.keep.job

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobRecordStateExtendedTest {

    @Test
    fun `waitingStates contains WAITING_FOR_START`() {
        assertTrue(JobState.waitingStates.contains(JobState.PENDING))
    }

    @Test
    fun `waitingStates does not contain NEED_USER_INPUT`() {
        assertFalse(JobState.waitingStates.contains(JobState.WAITING))
    }

    @Test
    fun `waitingStates does not contain RUNNING`() {
        assertFalse(JobState.waitingStates.contains(JobState.RUNNING))
    }

    @Test
    fun `waitingStates does not contain any terminated state`() {
        for (state in JobState.waitingStates) {
            assertFalse(state.terminated, "$state should not be terminated")
        }
    }

    @Test
    fun `waitingStates has exactly 1 entry`() {
        assertEquals(1, JobState.waitingStates.size)
    }

    @Test
    fun `terminatedStates contains all terminated entries`() {
        val expected = setOf(
            JobState.COMPLETED,
            JobState.FAILED,
            JobState.CANCELLED,
            JobState.TIMED_OUT,
            JobState.WAITING,
            JobState.UNKNOWN,
        )
        assertEquals(expected, JobState.terminatedStates.toSet())
    }

    @Test
    fun `terminatedStates does not contain non-terminated states`() {
        assertFalse(JobState.terminatedStates.contains(JobState.PENDING))
        assertFalse(JobState.terminatedStates.contains(JobState.RUNNING))
    }

    @Test
    fun `terminatedStates has exactly 6 entries`() {
        assertEquals(6, JobState.terminatedStates.size)
    }

    @Test
    fun `all states are either waiting, running, or terminated`() {
        for (state in JobState.entries) {
            val isWaiting = JobState.waitingStates.contains(state)
            val isTerminated = state.terminated
            val isRunning = state == JobState.RUNNING
            assertTrue(
                isWaiting || isTerminated || isRunning,
                "$state should be waiting, running, or terminated"
            )
        }
    }

    @Test
    fun `display text values are set correctly`() {
        assertEquals("Waiting", JobState.PENDING.displayName)
        assertEquals("Running", JobState.RUNNING.displayName)
        assertEquals("Succeeded", JobState.COMPLETED.displayName)
        assertEquals("Failed", JobState.FAILED.displayName)
        assertEquals("Cancelled", JobState.CANCELLED.displayName)
        assertEquals("Timed Out", JobState.TIMED_OUT.displayName)
        assertEquals("Need User Input", JobState.WAITING.displayName)
        assertEquals("Unknown", JobState.UNKNOWN.displayName)
    }
}
