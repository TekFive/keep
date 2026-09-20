package org.tekfive.keep.data

import java.util.UUID
import kotlin.reflect.KProperty1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DataCopyTest {
    class Values(val label: String?, val items: MutableList<String>, var count: Int) : Data()

    class UnstoredParameter(value: String) : Data() {
        val length = value.length
    }

    @Test
    fun `copies long data with the concrete type and persisted identity`() {
        val source = SimpleData("alice", 4).apply { linkToDB(15L) }
        val copy: SimpleData = source.copy()
        assertNotSame(source, copy)
        assertEquals(source, copy)
        assertEquals(15L, copy.id)
        assertTrue(copy.linkedToDb)
        copy.score = 8
        assertEquals(4, source.score)
    }

    @Test
    fun `copies UUID data with the concrete type and persisted identity`() {
        val id = UUID.randomUUID()
        val source = UuidSimpleData("alice", 4).apply { linkToDB(id) }
        val copy: UuidSimpleData = source.copy()
        assertNotSame(source, copy)
        assertEquals(source, copy)
        assertEquals(id, copy.id)
        assertTrue(copy.linkedToDb)
        copy.unlinkFromDB()
        assertEquals(id, source.id)
        assertTrue(source.linkedToDb)
        assertNull(copy.idOrNull)
    }

    @Test
    fun `copies unsaved objects without linking them to the database`() {
        val long = SimpleData("alice", 4)
        val uuid = UuidSimpleData("alice", 4)
        assertNull(long.copy().idOrNull)
        val uuidCopy = uuid.copy()
        assertNull(uuidCopy.idOrNull)
        assertFalse(uuidCopy.linkedToDb)
        assertNotEquals(uuid.id, uuidCopy.id)
    }

    @Test
    fun `preserves inherited constructor properties and the runtime type of base references`() {
        val source: Data = LeafData("inherited", true, 7).apply { linkToDB(3L) }
        val copy = source.copy() as LeafData
        assertNotSame(source, copy)
        assertEquals("inherited", copy.name)
        assertTrue(copy.active)
        assertEquals(7, copy.score)
        assertEquals(3L, copy.id)
    }

    @Test
    fun `copies nulls and shares referenced values like Kotlin data class copy`() {
        val source = Values(null, mutableListOf("one"), 1)
        val copy = source.copy()
        assertNull(copy.label)
        assertSame(source.items, copy.items)
    }

    @Test
    fun `preserves dirty state and keeps snapshot updates independent`() {
        val source = MutablePairData("alice", 1, true).apply { linkToDB(9L) }
        @Suppress("UNCHECKED_CAST")
        val properties = listOf(MutablePairData::score, MutablePairData::active) as List<KProperty1<Any, *>>
        source.snapshot(properties)
        source.score = 2
        val copy = source.copy()
        assertEquals(setOf("score"), copy.dirtyProperties)
        copy.active = false
        copy.snapshot(properties, setOf("score"))
        assertEquals(setOf("active"), copy.dirtyProperties)
        assertEquals(setOf("score"), source.dirtyProperties)
        assertTrue(source.active)
    }

    @Test
    fun `rejects constructor parameters whose original values cannot be recovered`() {
        val error = assertFailsWith<IllegalStateException> { UnstoredParameter("abc").copy() }
        assertTrue(error.message.orEmpty().contains("constructor parameter 'value'"))
    }
}
