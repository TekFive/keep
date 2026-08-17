package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.schema.AppSchema
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object UuidOnlySchema : AppSchema() {
    override val sequences = emptyList<String>()
    override val tables = listOf(UuidSimpleTable, UuidWidgetTable, UuidSimpleWidgetJoin, UuidNoteTable)
}

private object MixedIdentityJoin : TypedDataJoinTable<Long, SimpleData, UUID, UuidSimpleData>(
    "mixed_identity_join",
    SimpleTable,
    UuidSimpleTable,
)

class UuidDataTableTest {
    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction {
            SchemaUtils.drop(UuidNoteTable, UuidSimpleWidgetJoin, UuidWidgetTable, UuidSimpleTable)
            UuidOnlySchema.create()
        }
    }

    @AfterTest
    fun teardown() {
        transaction {
            SchemaUtils.drop(UuidNoteTable, UuidSimpleWidgetJoin, UuidWidgetTable, UuidSimpleTable)
        }
    }

    @Test
    fun `uuidV7 produces RFC UUID version 7 values`() {
        val ids = List(100) { uuidV7() }

        assertEquals(100, ids.distinct().size)
        assertTrue(ids.all { it.version() == 7 })
        assertTrue(ids.all { it.variant() == 2 })
    }

    @Test
    fun `create generates UUIDv7 and CRUD round trips`() {
        transaction {
            val data = UuidSimpleTable.create(UuidSimpleData("alice", 42))

            assertEquals(7, data.id.version())
            assertEquals(data.id, UuidSimpleTable.getById(data.id).id)
            assertEquals("alice", UuidSimpleTable.findById(data.id)?.name)

            data.score = 99
            UuidSimpleTable.save(data)
            assertEquals(99, UuidSimpleTable.findById(data.id)?.score)
            assertFalse(data.isDirty)

            val id = data.id
            UuidSimpleTable.delete(data)
            assertNull(data.idOrNull)
            assertNull(UuidSimpleTable.findById(id))
        }
    }

    @Test
    fun `create accepts caller supplied UUID`() {
        transaction {
            val id = UUID.fromString("018f47d2-a6b7-7000-8000-000000000001")
            val data = UuidSimpleTable.create(UuidSimpleData("known", 7), id)

            assertEquals(id, data.id)
            assertEquals("known", UuidSimpleTable.findById(id)?.name)
        }
    }

    @Test
    fun `UUID identities support routes collections and unique name checks`() {
        transaction {
            val first = UuidSimpleTable.create(UuidSimpleData("first", 1))
            val second = UuidSimpleTable.create(UuidSimpleData("second", 2))

            assertEquals(first.id.toString(), first.getParameter())
            assertEquals(listOf(first.id, second.id), listOf(first, second).toUuidIds())
            assertEquals(setOf(first.id, second.id), listOf(first, second, first).toDistinctUuidIds())
            assertTrue(UuidSimpleTable.isNameAlreadyTaken("first"))
            assertFalse(UuidSimpleTable.isNameAlreadyTaken("first", first.id))
        }
    }

    @Test
    fun `UUID foreign keys and join tables work`() {
        transaction {
            val simple = UuidSimpleTable.create(UuidSimpleData("simple", 1))
            val widget = UuidWidgetTable.create(UuidWidgetData("widget", 2))
            UuidNoteTable.create(UuidNoteData("note", simple.id))
            UuidSimpleWidgetJoin.insert {
                it[aId] = simple.id
                it[bId] = widget.id
            }

            val note = UuidNoteTable.findByUnique(simple.id, UuidNoteTable.simpleId)
            assertEquals(simple.id, note?.simpleId)
            val joined = UuidSimpleWidgetJoin.joinBoth().selectAll().single()
            assertEquals(simple, UuidSimpleWidgetJoin.mapA(joined))
            assertEquals(widget, UuidSimpleWidgetJoin.mapB(joined))
        }
    }

    @Test
    fun `typed joins may span Long and UUID identities`() {
        assertEquals(SimpleTable, MixedIdentityJoin.tableA)
        assertEquals(UuidSimpleTable, MixedIdentityJoin.tableB)
        assertEquals(SimpleTable.id, MixedIdentityJoin.aId.foreignKey?.targetOf(MixedIdentityJoin.aId))
        assertEquals(UuidSimpleTable.id, MixedIdentityJoin.bId.foreignKey?.targetOf(MixedIdentityJoin.bId))
    }

    @Test
    fun `AppSchema applies UUID table custom SQL hooks`() {
        transaction {
            var found = false
            TransactionManager.current().exec(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' " +
                    "AND indexname = 'uuid_simple_score_custom_idx'"
            ) { result -> found = result.next() }
            assertTrue(found)
        }
    }

    @Test
    fun `findByIds preserves order and duplicates`() {
        transaction {
            val first = UuidSimpleTable.create(UuidSimpleData("first", 1))
            val second = UuidSimpleTable.create(UuidSimpleData("second", 2))

            assertEquals(
                listOf(second.id, first.id, second.id),
                UuidSimpleTable.findByIds(listOf(second.id, first.id, second.id)).map { it.id },
            )
            assertTrue(UuidSimpleTable.selectAll().where { UuidSimpleTable.id eq first.id }.any())
        }
    }
}
