package org.tekfive.keep.job

import org.junit.jupiter.api.Test
import org.tekfive.jfk.JsonObject
import org.junit.jupiter.api.assertThrows
import org.tekfive.keep.job.db.QueryNode
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobRecordRegistryTest {

    @Test
    fun `register stores spec and get retrieves it`() {
        val registry = JobRegistry()
        val spec = TestJobSpec("test-job")
        registry.register(spec)
        assertEquals(spec, registry["test-job"])
    }

    @Test
    fun `get returns null for unknown type`() {
        val registry = JobRegistry()
        assertNull(registry["nonexistent"])
    }

    @Test
    fun `register throws on duplicate type id`() {
        val registry = JobRegistry()
        registry.register(TestJobSpec("test-job"))
        assertThrows<IllegalArgumentException> {
            registry.register(TestJobSpec("test-job"))
        }
    }

    @Test
    fun `jobTypeIds returns registered types`() {
        val registry = JobRegistry()
        registry.register(TestJobSpec("alpha"))
        registry.register(TestJobSpec("beta"))
        val ids = registry.jobTypeIds
        assertTrue(ids.contains("alpha"))
        assertTrue(ids.contains("beta"))
        assertEquals(2, ids.size)
    }

    @Test
    fun `jobTypeIds is empty initially`() {
        val registry = JobRegistry()
        assertTrue(registry.jobTypeIds.isEmpty())
    }
}

internal class TestJobSpec(override val jobTypeIdentifier: String) : JobSpec {
    override val estimateRuntime: Boolean = false
    override val jobPriority: Int? = null
    override val maxRetriesOnFailure: Int = 0
    override val minSecondsBetweenRetries: Int? = null
    override val retryExceptionBaseTypes: List<KClass<out Exception>> = emptyList()
    override fun getEstimatedRuntimeQueries(jobDetails: JsonObject): List<QueryNode> = emptyList()
    override fun createJob(): Job = object : Job {
        override fun execute(context: JobContext): JobResult = JobCompleted()
    }
}
