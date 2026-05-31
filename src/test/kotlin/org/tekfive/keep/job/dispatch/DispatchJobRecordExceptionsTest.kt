package org.tekfive.keep.job.dispatch

import org.junit.jupiter.api.Test
import org.tekfive.keep.job.JobState
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DispatchJobRecordExceptionsTest {

    // --- JobNotFoundException ---

    @Test
    fun `JobNotFoundException stores job id`() {
        val ex = JobNotFoundException(42L)
        assertEquals(42L, ex.jobId)
    }

    @Test
    fun `JobNotFoundException message contains job id`() {
        val ex = JobNotFoundException(99L)
        assertTrue(ex.message!!.contains("99"))
    }

    @Test
    fun `JobNotFoundException is a JobEndedException`() {
        val ex = JobNotFoundException(1L)
        assertIs<JobEndedException>(ex)
    }

    @Test
    fun `JobEndedException is an Exception`() {
        val ex = JobNotFoundException(1L)
        assertIs<Exception>(ex)
    }

    // --- TerminatedStateException ---

    @Test
    fun `TerminatedStateException stores state`() {
        val ex = TerminatedStateException(JobState.CANCELLED)
        assertEquals(JobState.CANCELLED, ex.state)
    }

    @Test
    fun `TerminatedStateException message contains state`() {
        val ex = TerminatedStateException(JobState.FAILED)
        assertTrue(ex.message!!.contains("FAILED"))
    }

    @Test
    fun `TerminatedStateException is a JobEndedException`() {
        val ex = TerminatedStateException(JobState.TIMED_OUT)
        assertIs<JobEndedException>(ex)
    }

    // --- OnCheckInSqlException ---

    @Test
    fun `OnCheckInSqlException stores the original SQLException`() {
        val cause = SQLException("connection lost", "08001")
        val ex = OnCheckInSqlException(cause)
        assertSame(cause, ex.sqlException)
    }

    @Test
    fun `OnCheckInSqlException is an Exception`() {
        val ex = OnCheckInSqlException(SQLException("test"))
        assertIs<Exception>(ex)
    }

    @Test
    fun `OnCheckInSqlException cause is the original SQLException`() {
        val cause = SQLException("connection lost")
        val ex = OnCheckInSqlException(cause)
        assertSame(cause, ex.cause)
    }
}
