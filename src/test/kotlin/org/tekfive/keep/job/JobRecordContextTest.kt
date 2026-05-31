package org.tekfive.keep.job

import org.junit.jupiter.api.Test
import org.tekfive.jfk.JsonObject
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobRecordContextTest {

    private fun createContext(attempt: Int, maxRetries: Int): JobContext {
        val job = object : Job {
            override fun execute(context: JobContext): JobResult {
                return JobCompleted()
            }

        }
        return object : JobContext {
            override val jobId: Long = 1L
            override val startedAt: Long = 0L
            override val type: String = "test"
            override val createdAt: Long = 0L
            override val attempt: Int = attempt
            override val maxRetries: Int = maxRetries
            override val estimatedRuntimeSeconds: Int? = null
            override val details: JsonObject? = null
            override val log: JobLogger = JobLogger(job, this, null)
            override fun checkIn(now: Long) {}
            override fun updateDetails(details: JsonObject) {}
        }
    }

    @Test
    fun `hasMoreRetries is true when attempt is less than maxRetries`() {
        val context = createContext(attempt = 1, maxRetries = 3)
        assertTrue(context.hasMoreRetries)
    }

    @Test
    fun `hasMoreRetries is true when attempt equals maxRetries`() {
        val context = createContext(attempt = 3, maxRetries = 3)
        assertTrue(context.hasMoreRetries)
    }

    @Test
    fun `hasMoreRetries is false when attempt exceeds maxRetries`() {
        val context = createContext(attempt = 4, maxRetries = 3)
        assertFalse(context.hasMoreRetries)
    }

    @Test
    fun `hasMoreRetries is true on first attempt with retries allowed`() {
        val context = createContext(attempt = 1, maxRetries = 1)
        assertTrue(context.hasMoreRetries)
    }

    @Test
    fun `hasMoreRetries is false when no retries allowed and first attempt`() {
        // attempt=1 <= maxRetries=0 is false, so hasMoreRetries is false
        val context = createContext(attempt = 1, maxRetries = 0)
        assertFalse(context.hasMoreRetries)
    }

    @Test
    fun `hasMoreRetries is true when attempt is 0 and maxRetries is 0`() {
        // Edge case: attempt=0 <= maxRetries=0 is true
        val context = createContext(attempt = 0, maxRetries = 0)
        assertTrue(context.hasMoreRetries)
    }
}
