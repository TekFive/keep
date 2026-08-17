package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.java.javaUUID
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class UuidStubData(val name: String, var active: Boolean) : UuidData()

private object UuidStubTuple : UuidDataTuple<UuidStubData>("uuid_stub", managedColumns = setOf("id")) {
    override val id = javaUUID("id")
    val name = varchar("name", 255)
    val active = bool("active")
}

private class UuidStubCache(
    private val rows: MutableMap<UUID, UuidStubData> = mutableMapOf(),
) : UuidDatabaseTupleCache<UuidStubData>(UuidStubTuple) {
    override val maxCachedRows = 2
    override val maxCacheSeconds = 300

    fun add(id: UUID, data: UuidStubData) {
        data.linkToDB(id)
        rows[id] = data
    }

    override fun fetchFromDb(id: UUID): UuidStubData? = rows[id]
}

class UuidDatabaseTupleCacheTest {
    @Test
    fun `UUID cache finds invalidates and bounds entries`() {
        val cache = UuidStubCache()
        val ids = List(3) { uuidV7() }
        ids.forEachIndexed { index, id -> cache.add(id, UuidStubData("row-$index", true)) }

        assertNotNull(cache.find(ids[0]))
        assertNotNull(cache.find(ids[1]))
        assertNotNull(cache.find(ids[2]))
        assertEquals(2, cache.size)

        cache.invalidate(ids[2])
        assertEquals(1, cache.size)
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.find(uuidV7()))
    }
}
