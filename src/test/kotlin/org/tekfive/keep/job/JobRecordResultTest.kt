package org.tekfive.keep.job

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JobRecordResultTest {

    @Test
    fun `JobSuccess is a JobResult`() {
        assertIs<JobResult>(JobCompleted())
    }

    @Test
    fun `JobFailure is a JobResult`() {
        assertIs<JobResult>(JobFailed("some error"))
    }

    @Test
    fun `JobFailure stores failure details`() {
        val failure = JobFailed("something went wrong")
        assertEquals("something went wrong", failure.errorMessage)
    }

    @Test
    fun `JobFailure retryIfAllowed defaults to false`() {
        val failure = JobFailed("error")
        assertFalse(failure.retryIfAllowed)
    }

    @Test
    fun `JobFailure retryIfAllowed can be set to true`() {
        val failure = JobFailed("error", retryIfAllowed = true)
        assertTrue(failure.retryIfAllowed)
    }
}
