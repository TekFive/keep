package org.tekfive.keep.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class SnapshotRow(val label: String) : Data()
private object SnapshotTable : DataTable<SnapshotRow>("snapshot_test") { val label = text("label") }
private fun row(id: Long, label: String = "row-$id") = SnapshotRow(label).also { it.linkToDB(id) }
private class SnapshotCache : DatabaseTableCache<SnapshotRow>(SnapshotTable) {
    override var maxCacheSeconds = 10
    var now = 0L
    var source: List<SnapshotRow> = emptyList()
    val loads = AtomicInteger()
    var loader: () -> List<SnapshotRow> = { source }
    override fun nanoTime() = now
    override fun fetchFromDb(): List<SnapshotRow> { loads.incrementAndGet(); return loader() }
}

class DatabaseTableCacheTest {
    @Test fun `list size and ID lookups share a single lazy snapshot including missing IDs`() {
        val cache = SnapshotCache()
        cache.source = listOf(row(1), row(2))
        assertEquals(0, cache.loads.get())
        val all = cache.findAll()
        assertEquals(2, cache.size)
        assertSame(all[0], cache[1])
        assertNull(cache.find(3))
        assertNull(cache.find(3))
        assertFailsWith<NoSuchElementException> { cache[3] }
        assertSame(all, cache.findAll())
        assertEquals(1, cache.loads.get())
    }

    @Test fun `empty tables are cached until expiration`() {
        val cache = SnapshotCache()
        assertTrue(cache.findAll().isEmpty())
        cache.source = listOf(row(1))
        assertEquals(0, cache.size)
        assertNull(cache.find(1))
        assertEquals(1, cache.loads.get())
        cache.now = 10_000_000_000L
        assertEquals(1, cache.size)
        assertEquals(2, cache.loads.get())
    }

    @Test fun `expiration replaces the entire snapshot and does not slide on reads`() {
        val cache = SnapshotCache()
        cache.source = listOf(row(1))
        val old = cache.findAll()
        cache.source = listOf(row(2))
        cache.now = 9_000_000_000L
        assertSame(old, cache.findAll())
        cache.now = 10_000_000_000L
        assertEquals(listOf(2L), cache.findAll().map { it.id })
        assertNull(cache.find(1))
        assertEquals(listOf(1L), old.map { it.id })
        assertEquals(2, cache.loads.get())
    }

    @Test fun `TTL starts after load completes`() {
        val cache = SnapshotCache()
        cache.loader = { cache.now += 5_000_000_000L; listOf(row(1)) }
        cache.findAll()
        cache.now = 14_000_000_000L
        cache.findAll()
        assertEquals(1, cache.loads.get())
        cache.now = 15_000_000_000L
        cache.findAll()
        assertEquals(2, cache.loads.get())
    }

    @Test fun `refresh invalidate and clear operate on the complete snapshot`() {
        val cache = SnapshotCache()
        cache.source = listOf(row(1))
        cache.findAll()
        cache.source = listOf(row(2))
        assertEquals(listOf(2L), cache.refresh().map { it.id })
        assertNull(cache.find(1))
        cache.source = listOf(row(3))
        cache.invalidate()
        assertEquals(2, cache.loads.get())
        assertEquals(3L, cache[3].id)
        cache.clear()
        assertEquals(3, cache.loads.get())
        cache.findAll()
        assertEquals(4, cache.loads.get())
    }

    @Test fun `failed refresh never publishes partial data and expired reads retry`() {
        val cache = SnapshotCache()
        cache.source = listOf(row(1))
        val old = cache.findAll()
        cache.loader = { error("database unavailable") }
        assertFailsWith<IllegalStateException> { cache.refresh() }
        assertSame(old, cache.findAll())
        cache.now = 10_000_000_000L
        assertFailsWith<IllegalStateException> { cache.findAll() }
        cache.loader = { listOf(row(2)) }
        assertEquals(listOf(2L), cache.findAll().map { it.id })
    }

    @Test fun `disabled caching reloads each read and discards previous retained snapshot`() {
        for (seconds in listOf(0, -1)) {
            val cache = SnapshotCache()
            cache.source = listOf(row(1))
            cache.findAll()
            cache.maxCacheSeconds = seconds
            cache.source = listOf(row(2))
            assertEquals(2L, cache.findAll().single().id)
            assertNull(cache.find(1))
            assertEquals(3, cache.loads.get())
            cache.maxCacheSeconds = 10
            cache.source = listOf(row(3))
            assertEquals(3L, cache.findAll().single().id)
        }
    }

    @Test fun `snapshots defensively copy the source and prevent structural mutation`() {
        val cache = SnapshotCache()
        val source = mutableListOf(row(1))
        cache.source = source
        val snapshot = cache.findAll()
        source.clear()
        assertEquals(1, snapshot.size)
        assertFailsWith<UnsupportedOperationException> { (snapshot as MutableList).clear() }
        assertEquals(1L, cache[1].id)
    }

    @Test fun `duplicate and unlinked IDs are rejected without replacing a valid snapshot`() {
        val cache = SnapshotCache()
        cache.source = listOf(row(1))
        val old = cache.findAll()
        cache.source = listOf(row(2), row(2))
        assertFailsWith<IllegalArgumentException> { cache.refresh() }
        assertSame(old, cache.findAll())
        cache.source = listOf(SnapshotRow("unsaved"))
        assertFailsWith<IllegalArgumentException> { cache.refresh() }
        assertSame(old, cache.findAll())
    }

    @Test fun `concurrent cold readers share one complete load`() {
        val cache = SnapshotCache()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        cache.loader = {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            listOf(row(1), row(2))
        }
        val executor = Executors.newFixedThreadPool(6)
        try {
            val reads = List(6) { executor.submit<List<SnapshotRow>> { cache.findAll() } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            release.countDown()
            val snapshots = reads.map { it.get(5, TimeUnit.SECONDS) }
            snapshots.forEach { assertSame(snapshots.first(), it) }
            assertEquals(listOf(1L, 2L), snapshots.first().map { it.id })
            assertEquals(1, cache.loads.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
