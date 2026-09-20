package org.tekfive.keep.data

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataCopyIntegrationTest {
    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction { SchemaUtils.create(SimpleTable, UuidSimpleTable) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(UuidSimpleTable, SimpleTable) }
    }

    @Test
    fun `saving a modified long copy updates the original row without changing the source object`() = transaction {
        val source = SimpleTable.create(SimpleData("alice", 1))
        val copy = source.copy()
        assertFalse(copy.isDirty)
        copy.score = 2
        assertTrue(copy.isDirty)
        SimpleTable.save(copy)
        assertFalse(copy.isDirty)
        assertFalse(source.isDirty)
        assertEquals(1, source.score)
        assertEquals(source.id, copy.id)
        assertEquals(1L, SimpleTable.selectAll().count())
        assertEquals(2, SimpleTable.getById(source.id).score)
    }

    @Test
    fun `saving a dirty UUID copy retains pending edits and updates the original row`() = transaction {
        val source = UuidSimpleTable.create(UuidSimpleData("alice", 1))
        source.score = 2
        val copy = source.copy()
        assertEquals(setOf("score"), copy.dirtyProperties)
        UuidSimpleTable.save(copy)
        assertFalse(copy.isDirty)
        assertTrue(source.isDirty)
        assertEquals(source.id, copy.id)
        assertEquals(1L, UuidSimpleTable.selectAll().count())
        assertEquals(2, UuidSimpleTable.getById(source.id).score)
    }
}
