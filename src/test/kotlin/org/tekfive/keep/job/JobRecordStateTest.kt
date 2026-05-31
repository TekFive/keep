package org.tekfive.keep.job

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobRecordStateTest {

    @Test
    fun `map returns correct state for each id`() {
        assertEquals(JobState.PENDING, JobState.map(1))
        assertEquals(JobState.RUNNING, JobState.map(2))
        assertEquals(JobState.COMPLETED, JobState.map(3))
        assertEquals(JobState.FAILED, JobState.map(4))
        assertEquals(JobState.CANCELLED, JobState.map(5))
        assertEquals(JobState.TIMED_OUT, JobState.map(6))
        assertEquals(JobState.WAITING, JobState.map(7))
        assertEquals(JobState.UNKNOWN, JobState.map(8))
    }

    @Test
    fun `mapOptional returns null for null input`() {
        assertNull(JobState.mapOptional(null))
    }

    @Test
    fun `mapOptional returns null for unknown id`() {
        assertNull(JobState.mapOptional(99))
    }

    @Test
    fun `non-terminated states`() {
        assertFalse(JobState.PENDING.terminated)
        assertFalse(JobState.RUNNING.terminated)
    }

    @Test
    fun `terminated states`() {
        assertTrue(JobState.COMPLETED.terminated)
        assertTrue(JobState.FAILED.terminated)
        assertTrue(JobState.CANCELLED.terminated)
        assertTrue(JobState.TIMED_OUT.terminated)
        assertTrue(JobState.WAITING.terminated)
        assertTrue(JobState.UNKNOWN.terminated)
    }

    @Test
    fun `each state has unique id`() {
        val ids = JobState.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
