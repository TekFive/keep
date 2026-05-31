package org.tekfive.keep.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionCacheTest {

    @Test
    fun `get returns null for missing key`() {
        TransactionCache {
            val cache = TransactionCache.current!!
            assertNull(cache.get<String>("missing"))
        }
    }

    @Test
    fun `put and get round-trips value`() {
        TransactionCache {
            val cache = TransactionCache.current!!
            cache.put("key", "value")
            assertEquals("value", cache.get<String>("key"))
        }
    }

    @Test
    fun `containsKey returns false for missing key`() {
        TransactionCache {
            val cache = TransactionCache.current!!
            assertFalse(cache.containsKey("missing"))
        }
    }

    @Test
    fun `containsKey returns true for null value`() {
        TransactionCache {
            val cache = TransactionCache.current!!
            cache.put("key", null)
            assertTrue(cache.containsKey("key"))
        }
    }

    @Test
    fun `get returns null for null cached value`() {
        TransactionCache {
            val cache = TransactionCache.current!!
            cache.put("key", null)
            assertNull(cache.get<String>("key"))
        }
    }

    @Test
    fun `getOrPut returns cached value without invoking default`() {
        TransactionCache {
            val cache = TransactionCache.current!!
            cache.put("key", "existing")
            var invoked = false
            val result = cache.getOrPut<String>("key") {
                invoked = true
                "new"
            }
            assertEquals("existing", result)
            assertFalse(invoked)
        }
    }

    @Test
    fun `getOrPut returns cached null without invoking default`() {
        TransactionCache {
            val cache = TransactionCache.current!!
            cache.put("key", null)
            var invoked = false
            val result = cache.getOrPut<String>("key") {
                invoked = true
                "new"
            }
            assertNull(result)
            assertFalse(invoked)
        }
    }

    @Test
    fun `getOrPut computes and caches value on miss`() {
        TransactionCache {
            val cache = TransactionCache.current!!
            val result = cache.getOrPut("key") { "computed" }
            assertEquals("computed", result)
            assertEquals("computed", cache.get<String>("key"))
        }
    }

    @Test
    fun `getOrPut computes and caches null on miss`() {
        TransactionCache {
            val cache = TransactionCache.current!!
            val result = cache.getOrPut<String>("key") { null }
            assertNull(result)
            assertTrue(cache.containsKey("key"))
        }
    }

    @Test
    fun `remove removes entry`() {
        TransactionCache {
            val cache = TransactionCache.current!!
            cache.put("key", "value")
            cache.remove("key")
            assertFalse(cache.containsKey("key"))
        }
    }

    @Test
    fun `nested invocation reuses existing cache`() {
        TransactionCache {
            val outer = TransactionCache.current!!
            outer.put("key", "outer")
            TransactionCache {
                val inner = TransactionCache.current!!
                assertEquals("outer", inner.get<String>("key"))
            }
        }
    }

    @Test
    fun `current returns null outside cache scope`() {
        assertNull(TransactionCache.current)
    }
}
