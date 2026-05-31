package org.tekfive.keep.data

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.db.TransactionCache
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataTupleFindByIdsTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction {
            SchemaUtils.create(SimpleTable)
        }
    }

    @AfterTest
    fun teardown() {
        transaction {
            SchemaUtils.drop(SimpleTable)
        }
    }

    @Test
    fun `findByIds returns empty list for empty input`() {
        transaction {
            val result = SimpleTable.findByIds(emptyList())
            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `findByIds returns matching data`() {
        transaction {
            val a = SimpleData("alice", 10)
            val b = SimpleData("bob", 20)
            val c = SimpleData("charlie", 30)
            SimpleTable.create(a)
            SimpleTable.create(b)
            SimpleTable.create(c)

            val result = SimpleTable.findByIds(listOf(a.id, c.id))
            assertEquals(2, result.size)
            val names = result.map { it.name }.toSet()
            assertTrue("alice" in names)
            assertTrue("charlie" in names)
        }
    }

    @Test
    fun `findByIds skips nonexistent ids`() {
        transaction {
            val a = SimpleData("alice", 10)
            SimpleTable.create(a)

            val result = SimpleTable.findByIds(listOf(a.id, 99999L))
            assertEquals(1, result.size)
            assertEquals("alice", result[0].name)
        }
    }

    @Test
    fun `findByIds uses transaction cache for cached entries`() {
        transaction {
            val a = SimpleData("alice", 10)
            val b = SimpleData("bob", 20)
            SimpleTable.create(a)
            SimpleTable.create(b)

            TransactionCache {
                // Pre-populate cache via findById
                val cachedA = SimpleTable.findById(a.id)
                assertEquals("alice", cachedA!!.name)

                // findByIds should use cache for a.id and query only b.id
                val result = SimpleTable.findByIds(listOf(a.id, b.id))
                assertEquals(2, result.size)
            }
        }
    }

    @Test
    fun `findByIds preserves input order when cache and database results are mixed`() {
        transaction {
            val a = SimpleData("alice", 10)
            val b = SimpleData("bob", 20)
            val c = SimpleData("charlie", 30)
            SimpleTable.create(a)
            SimpleTable.create(b)
            SimpleTable.create(c)

            TransactionCache {
                assertEquals("bob", SimpleTable.findById(b.id)?.name)

                val result = SimpleTable.findByIds(listOf(c.id, b.id, a.id, 99999L))

                assertEquals(listOf("charlie", "bob", "alice"), result.map { it.name })
            }
        }
    }

    @Test
    fun `findByIds caches results for subsequent lookups`() {
        transaction {
            val a = SimpleData("alice", 10)
            SimpleTable.create(a)

            TransactionCache {
                val cache = TransactionCache.current!!

                // Not cached yet
                val key = Pair(SimpleTable, a.id)
                assertTrue(!cache.containsKey(key))

                SimpleTable.findByIds(listOf(a.id))

                // Now cached
                assertTrue(cache.containsKey(key))
                assertEquals("alice", cache.get<SimpleData>(key)!!.name)
            }
        }
    }

    @Test
    fun `findByIds caches null for nonexistent ids`() {
        transaction {
            TransactionCache {
                val cache = TransactionCache.current!!
                val key = Pair(SimpleTable, 99999L)

                SimpleTable.findByIds(listOf(99999L))

                // Null should be cached
                assertTrue(cache.containsKey(key))
                assertNull(cache.get<SimpleData>(key))
            }
        }
    }

    @Test
    fun `findById caches null and skips re-query`() {
        transaction {
            TransactionCache {
                val cache = TransactionCache.current!!
                val key = Pair(SimpleTable, 99999L)

                // First call queries DB and caches null
                val first = SimpleTable.findById(99999L)
                assertNull(first)
                assertTrue(cache.containsKey(key))

                // Second call should return cached null without re-query
                val second = SimpleTable.findById(99999L)
                assertNull(second)
            }
        }
    }
}
