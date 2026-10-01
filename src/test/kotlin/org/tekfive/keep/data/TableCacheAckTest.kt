package org.tekfive.keep.data

import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.configuration.AckSource
import org.tekfive.ack.sources.MapSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class AckCacheRow : Data()
private class AckUuidCacheRow : UuidData()
private class AckSnapshotCache(name: String) : DatabaseTableCache<AckCacheRow>(object : DataTable<AckCacheRow>(name) {}) {
    var now = 0L
    var loads = 0
    override fun nanoTime() = now
    override fun fetchFromDb(): List<AckCacheRow> { loads++; return emptyList() }
}

class TableCacheAckTest {
    private lateinit var previousSources: List<AckSource>

    @BeforeTest fun setup() {
        previousSources = AckRegistry.sources.toList()
        AckRegistry.clear()
    }

    @AfterTest fun cleanup() {
        AckRegistry.clear()
        previousSources.forEach { AckRegistry.addSource(it) }
    }

    @Test fun `default lifetime and configuration name include normalized table and schema`() {
        val plain = AckSnapshotCache("countries")
        assertEquals("TABLE_CACHE_COUNTRIES_MAX_CACHE_SECONDS", plain.maxCacheSecondsProperty.name)
        assertEquals(300, plain.maxCacheSeconds)
        val qualified = AckSnapshotCache("\"warehouse\".\"sales-items\"")
        assertEquals("TABLE_CACHE_WAREHOUSE_SALES_ITEMS_MAX_CACHE_SECONDS", qualified.maxCacheSecondsProperty.name)
        assertEquals(300, qualified.maxCacheSeconds)
    }

    @Test fun `Long and UUID caches resolve independent per-table Ack settings`() {
        AckRegistry.addSource(MapSource(mapOf(
            "TABLE_CACHE_FIRST_COUNTRIES_MAX_CACHE_SECONDS" to "15",
            "TABLE_CACHE_SECOND_COUNTRIES_MAX_CACHE_SECONDS" to "90",
        )))
        val longCache = AckSnapshotCache("first.countries")
        val uuidCache = object : UuidDatabaseTableCache<AckUuidCacheRow>(
            object : UuidDataTable<AckUuidCacheRow>("second.countries") {},
        ) {}
        assertEquals(15, longCache.maxCacheSeconds)
        assertEquals(90, uuidCache.maxCacheSeconds)
    }

    @Test fun `configured lifetime determines when the snapshot expires`() {
        AckRegistry.addSource(MapSource(mapOf("TABLE_CACHE_COUNTRIES_MAX_CACHE_SECONDS" to "2")))
        val cache = AckSnapshotCache("countries")
        cache.findAll()
        cache.now = 1_999_999_999L
        cache.findAll()
        assertEquals(1, cache.loads)
        cache.now = 2_000_000_000L
        cache.findAll()
        assertEquals(2, cache.loads)
    }

    @Test fun `zero configured lifetime disables snapshot retention`() {
        AckRegistry.addSource(MapSource(mapOf("TABLE_CACHE_COUNTRIES_MAX_CACHE_SECONDS" to "0")))
        val cache = AckSnapshotCache("countries")
        cache.findAll()
        cache.findAll()
        assertEquals(2, cache.loads)
    }

    @Test fun `subclass can override the Ack-backed lifetime`() {
        AckRegistry.addSource(MapSource(mapOf("TABLE_CACHE_COUNTRIES_MAX_CACHE_SECONDS" to "90")))
        val cache = object : DatabaseTableCache<AckCacheRow>(object : DataTable<AckCacheRow>("countries") {}) {
            override val maxCacheSeconds = 7
        }
        assertEquals(7, cache.maxCacheSeconds)
    }
}
