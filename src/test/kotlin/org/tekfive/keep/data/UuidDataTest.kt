package org.tekfive.keep.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UuidDataTest {
    @Test
    fun `unsaved instances expose stable distinct temporary IDs without being linked`() {
        val first = UuidSimpleData("alice", 42)
        val second = UuidSimpleData("alice", 42)
        val temporary = first.id

        assertEquals(temporary, first.id)
        assertNotEquals(temporary, second.id)
        assertNull(first.idOrNull)
        assertFalse(first.linkedToDb)
        assertTrue(first.notLinkedToDb)
        assertEquals("Create", first.getSaveAction())
        assertEquals(temporary.toString(), first.getParameter())
        assertEquals(listOf(temporary), listOf(first).toUuidIds())
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals("{\"name\":\"alice\",\"score\":42}", first.toJsonString())
    }

    @Test
    fun `linking replaces the temporary ID and unlinking restores it`() {
        val data = UuidSimpleData("alice", 42)
        val temporary = data.id
        data.score = 99
        assertEquals(temporary, data.id)

        val persisted = uuidV7()
        data.linkToDB(persisted)
        assertEquals(persisted, data.id)
        assertEquals(persisted, data.idOrNull)
        assertEquals("Update", data.getSaveAction())
        assertEquals(mapOf("id" to persisted), data.additionalJsonValues())

        data.unlinkFromDB()
        assertEquals(temporary, data.id)
        assertNull(data.idOrNull)
        assertEquals("Create", data.getSaveAction())
        assertTrue(data.additionalJsonValues().isEmpty())
    }

    @Test
    fun `long IDs still require linking before access`() {
        val data = SimpleData("alice", 42)
        assertNull(data.idOrNull)
        assertFailsWith<IllegalStateException> { data.id }
    }
}
