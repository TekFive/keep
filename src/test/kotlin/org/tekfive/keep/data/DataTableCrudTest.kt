package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataTableCrudTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction {
            SchemaUtils.create(SimpleTable)
            SchemaUtils.create(MutablePairTable)
            SchemaUtils.create(HierarchyTable)
        }
    }

    @AfterTest
    fun teardown() {
        transaction {
            SchemaUtils.drop(HierarchyTable, MutablePairTable, SimpleTable)
        }
    }

    // -- create ---------------------------------------------------------------

    @Test
    fun `create inserts row and links id`() {
        transaction {
            val data = SimpleData("alice", 42)
            assertNull(data.idOrNull)

            SimpleTable.create(data)

            assertNotNull(data.idOrNull)
            assertTrue(data.id > 0)
        }
    }

    @Test
    fun `create persists all properties`() {
        transaction {
            val data = SimpleData("bob", 99)
            SimpleTable.create(data)

            val row = SimpleTable.selectAll().where { SimpleTable.id eq data.id }.single()
            assertEquals("bob", row[SimpleTable.name])
            assertEquals(99, row[SimpleTable.score])
        }
    }

    @Test
    fun `create with preallocated id inserts row and links id`() {
        transaction {
            val data = SimpleData("preallocated", 77)

            SimpleTable.create(data, 42L)

            assertEquals(42L, data.id)
            val row = SimpleTable.selectAll().where { SimpleTable.id eq 42L }.single()
            assertEquals("preallocated", row[SimpleTable.name])
            assertEquals(77, row[SimpleTable.score])
        }
    }

    @Test
    fun `create fails if data already has id`() {
        transaction {
            val data = SimpleData("alice", 42)
            SimpleTable.create(data)

            assertFailsWith<IllegalStateException> {
                SimpleTable.create(data)
            }
        }
    }

    @Test
    fun `create with preallocated id fails if data already has id`() {
        transaction {
            val data = SimpleData("alice", 42)
            SimpleTable.create(data)

            assertFailsWith<IllegalStateException> {
                SimpleTable.create(data, 99L)
            }
        }
    }

    @Test
    fun `create works with hierarchy data`() {
        transaction {
            val data = LeafData("charlie", true, 77)
            HierarchyTable.create(data)

            val row = HierarchyTable.selectAll().where { HierarchyTable.id eq data.id }.single()
            assertEquals("charlie", row[HierarchyTable.name])
            assertEquals(true, row[HierarchyTable.active])
            assertEquals(77, row[HierarchyTable.score])
        }
    }

    // -- update ---------------------------------------------------------------

    @Test
    fun `update persists changed var properties`() {
        transaction {
            val data = SimpleData("alice", 42)
            SimpleTable.create(data)

            data.score = 100
            SimpleTable.update(data, SimpleTable.score)

            val row = SimpleTable.selectAll().where { SimpleTable.id eq data.id }.single()
            assertEquals(100, row[SimpleTable.score])
            // val property unchanged
            assertEquals("alice", row[SimpleTable.name])
        }
    }

    @Test
    fun `update fails if data has no id`() {
        transaction {
            val data = SimpleData("alice", 42)
            assertFailsWith<IllegalStateException> {
                SimpleTable.update(data, SimpleTable.score)
            }
        }
    }

    @Test
    fun `update works with hierarchy data`() {
        transaction {
            val data = LeafData("dave", true, 50)
            HierarchyTable.create(data)

            data.active = false
            data.score = 99
            HierarchyTable.update(data, HierarchyTable.active, HierarchyTable.score)

            val row = HierarchyTable.selectAll().where { HierarchyTable.id eq data.id }.single()
            assertEquals("dave", row[HierarchyTable.name])
            assertEquals(false, row[HierarchyTable.active])
            assertEquals(99, row[HierarchyTable.score])
        }
    }

    @Test
    fun `update writes only explicitly specified columns`() {
        transaction {
            val data = MutablePairData("erin", 10, true)
            MutablePairTable.create(data)

            data.score = 20
            data.active = false
            MutablePairTable.update(data, MutablePairTable.score)

            val row = MutablePairTable.selectAll().where { MutablePairTable.id eq data.id }.single()
            assertEquals(20, row[MutablePairTable.score])
            assertEquals(true, row[MutablePairTable.active])
            assertEquals(setOf("active"), data.dirtyProperties)
        }
    }

    // -- save -----------------------------------------------------------------

    @Test
    fun `save creates when not linked to db`() {
        transaction {
            val data = SimpleData("grace", 70)
            assertNull(data.idOrNull)

            SimpleTable.save(data)

            assertNotNull(data.idOrNull)
            val row = SimpleTable.selectAll().where { SimpleTable.id eq data.id }.single()
            assertEquals("grace", row[SimpleTable.name])
            assertEquals(70, row[SimpleTable.score])
        }
    }

    @Test
    fun `save updates when linked to db`() {
        transaction {
            val data = SimpleData("hank", 30)
            SimpleTable.create(data)

            data.score = 85
            SimpleTable.save(data)

            val row = SimpleTable.selectAll().where { SimpleTable.id eq data.id }.single()
            assertEquals("hank", row[SimpleTable.name])
            assertEquals(85, row[SimpleTable.score])
        }
    }

    // -- delete ---------------------------------------------------------------

    @Test
    fun `delete removes row and unlinks id`() {
        transaction {
            val data = SimpleData("alice", 42)
            SimpleTable.create(data)
            val savedId = data.id

            SimpleTable.delete(data)

            assertNull(data.idOrNull)
            assertTrue(SimpleTable.selectAll().where { SimpleTable.id eq savedId }.empty())
        }
    }

    @Test
    fun `delete fails if data has no id`() {
        transaction {
            val data = SimpleData("alice", 42)
            assertFailsWith<IllegalStateException> {
                SimpleTable.delete(data)
            }
        }
    }

    // -- round-trip with map --------------------------------------------------

    @Test
    fun `create then map round-trips data`() {
        transaction {
            val original = SimpleData("eve", 55)
            SimpleTable.create(original)

            val row = SimpleTable.selectAll().where { SimpleTable.id eq original.id }.single()
            val loaded = SimpleTable.map(row)

            assertEquals(original.name, loaded.name)
            assertEquals(original.score, loaded.score)
            assertEquals(original.id, loaded.id)
        }
    }

    @Test
    fun `create update then map round-trips hierarchy data`() {
        transaction {
            val original = LeafData("frank", false, 10)
            HierarchyTable.create(original)

            original.active = true
            original.score = 88
            HierarchyTable.update(original, HierarchyTable.active, HierarchyTable.score)

            val row = HierarchyTable.selectAll().where { HierarchyTable.id eq original.id }.single()
            val loaded = HierarchyTable.map(row)

            assertEquals("frank", loaded.name)
            assertEquals(true, loaded.active)
            assertEquals(88, loaded.score)
            assertEquals(original.id, loaded.id)
        }
    }
}
