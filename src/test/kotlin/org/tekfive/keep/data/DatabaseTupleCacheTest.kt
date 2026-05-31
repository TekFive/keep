package org.tekfive.keep.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class StubData(val name: String, var active: Boolean) : Data()

/**
 * A cache backed by an in-memory map instead of a real database, allowing
 * cache behavior to be tested without a database connection.
 */
private class StubCache(
    private val store: MutableMap<Long, StubData> = mutableMapOf(),
    override val maxCachedRows: Int = 100,
    override val maxCacheSeconds: Int = 300,
) : DatabaseTupleCache<StubData>(StubTuple) {

    fun putInStore(id: Long, data: StubData) {
        data.linkToDB(id)
        store[id] = data
    }

    override fun fetchFromDb(id: Long): StubData? {
        return store[id]
    }

    private object StubTuple : DataTuple<StubData>("stub", managedColumns = setOf("id")) {
        override val id = long("id")
        val name = varchar("name", 255)
        val active = bool("active")
    }
}

class DatabaseTupleCacheTest {

    @Test
    fun `find returns and caches data`() {
        val cache = StubCache()
        val data = StubData("alice", true)
        cache.putInStore(1L, data)

        val result = cache.find(1L)
        assertNotNull(result)
        assertEquals("alice", result.name)
        assertEquals(1, cache.size)
    }

    @Test
    fun `find returns cached entry on subsequent calls`() {
        val cache = StubCache()
        val data = StubData("alice", true)
        cache.putInStore(1L, data)

        val first = cache.find(1L)
        val second = cache.find(1L)
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(1, cache.size)
    }

    @Test
    fun `get throws for nonexistent id`() {
        val cache = StubCache()
        assertFailsWith<NoSuchElementException> {
            cache[999L]
        }
    }

    @Test
    fun `find returns null for nonexistent id`() {
        val cache = StubCache()
        val result = cache.find(999L)
        assertNull(result)
        assertEquals(0, cache.size)
    }

    @Test
    fun `find bypasses cache when caching is disabled`() {
        val cache = StubCache(maxCachedRows = 0)
        val data = StubData("no-cache", true)
        cache.putInStore(1L, data)

        val result = cache.find(1L)
        assertNotNull(result)
        assertEquals("no-cache", result.name)
        assertEquals(0, cache.size)
    }

    @Test
    fun `set explicitly caches data`() {
        val cache = StubCache()
        val data = StubData("bob", false)
        data.linkToDB(1L)

        cache[1L] = data
        assertEquals(1, cache.size)
    }

    @Test
    fun `set does not cache when caching is disabled`() {
        val cache = StubCache(maxCachedRows = 0)
        val data = StubData("bob", false)
        data.linkToDB(1L)

        cache[1L] = data
        assertEquals(0, cache.size)
    }

    @Test
    fun `invalidate removes cached entry`() {
        val cache = StubCache()
        val data = StubData("alice", true)
        cache.putInStore(1L, data)

        cache.find(1L)
        assertEquals(1, cache.size)

        cache.invalidate(1L)
        assertEquals(0, cache.size)
    }

    @Test
    fun `clear removes all cached entries`() {
        val cache = StubCache()
        cache.putInStore(1L, StubData("a", true))
        cache.putInStore(2L, StubData("b", true))

        cache.find(1L)
        cache.find(2L)
        assertEquals(2, cache.size)

        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun `eviction removes oldest when over max`() {
        val cache = StubCache(maxCachedRows = 2)
        cache.putInStore(1L, StubData("a", true))
        cache.putInStore(2L, StubData("b", true))
        cache.putInStore(3L, StubData("c", true))

        cache.find(1L)
        cache.find(2L)
        assertEquals(2, cache.size)

        cache.find(3L)
        assertEquals(2, cache.size)
    }
}
