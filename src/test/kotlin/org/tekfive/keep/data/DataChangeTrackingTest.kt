package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataChangeTrackingTest {

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

    // -- isDirty / dirtyProperties on new objects -----------------------------

    @Test
    fun `new object without snapshot is dirty`() {
        val data = SimpleData("alice", 42)
        assertTrue(data.isDirty)
    }

    @Test
    fun `new object dirtyProperties is empty before table interaction`() {
        val data = SimpleData("alice", 42)
        // No var property list yet (set by DataTable on create/map), so dirtyProperties is empty.
        // isDirty is still true because there's no snapshot.
        assertTrue(data.dirtyProperties.isEmpty())
        assertTrue(data.isDirty)
    }

    // -- isDirty after create -------------------------------------------------

    @Test
    fun `object is clean after create`() {
        transaction {
            val data = SimpleData("alice", 42)
            SimpleTable.create(data)
            assertFalse(data.isDirty)
            assertTrue(data.dirtyProperties.isEmpty())
        }
    }

    @Test
    fun `object becomes dirty after mutating var property`() {
        transaction {
            val data = SimpleData("alice", 42)
            SimpleTable.create(data)

            data.score = 100
            assertTrue(data.isDirty)
            assertEquals(setOf("score"), data.dirtyProperties)
        }
    }

    @Test
    fun `object is clean if var set back to original value`() {
        transaction {
            val data = SimpleData("alice", 42)
            SimpleTable.create(data)

            data.score = 100
            assertTrue(data.isDirty)

            data.score = 42
            assertFalse(data.isDirty)
        }
    }

    // -- isDirty after update -------------------------------------------------

    @Test
    fun `object is clean after update`() {
        transaction {
            val data = SimpleData("alice", 42)
            SimpleTable.create(data)
            data.score = 100
            SimpleTable.update(data, SimpleTable.score)
            assertFalse(data.isDirty)
        }
    }

    @Test
    fun `snapshot refreshes after update`() {
        transaction {
            val data = SimpleData("alice", 42)
            SimpleTable.create(data)

            data.score = 100
            SimpleTable.update(data, SimpleTable.score)
            assertFalse(data.isDirty)

            // Now 100 is the clean baseline
            data.score = 42
            assertTrue(data.isDirty)
            assertEquals(setOf("score"), data.dirtyProperties)
        }
    }

    // -- isDirty after map (load) ---------------------------------------------

    @Test
    fun `loaded object is clean`() {
        transaction {
            val original = SimpleData("alice", 42)
            SimpleTable.create(original)

            val row = SimpleTable.selectAll().where { SimpleTable.id eq original.id }.single()
            val loaded = SimpleTable.map(row)

            assertFalse(loaded.isDirty)
            assertTrue(loaded.dirtyProperties.isEmpty())
        }
    }

    @Test
    fun `loaded object becomes dirty after mutation`() {
        transaction {
            val original = SimpleData("alice", 42)
            SimpleTable.create(original)

            val row = SimpleTable.selectAll().where { SimpleTable.id eq original.id }.single()
            val loaded = SimpleTable.map(row)

            loaded.score = 99
            assertTrue(loaded.isDirty)
            assertEquals(setOf("score"), loaded.dirtyProperties)
        }
    }

    // -- Explicit-column update ----------------------------------------------

    @Test
    fun `update writes explicit column even when nothing is dirty`() {
        transaction {
            val data = SimpleData("alice", 42)
            SimpleTable.create(data)

            SimpleTable.update(data, SimpleTable.score)

            val row = SimpleTable.selectAll().where { SimpleTable.id eq data.id }.single()
            assertEquals("alice", row[SimpleTable.name])
            assertEquals(42, row[SimpleTable.score])
        }
    }

    // -- Hierarchy change tracking --------------------------------------------

    @Test
    fun `hierarchy object is clean after create`() {
        transaction {
            val data = LeafData("charlie", true, 77)
            HierarchyTable.create(data)
            assertFalse(data.isDirty)
        }
    }

    @Test
    fun `hierarchy tracks dirty properties from multiple levels`() {
        transaction {
            val data = LeafData("charlie", true, 77)
            HierarchyTable.create(data)

            data.active = false
            assertEquals(setOf("active"), data.dirtyProperties)

            data.score = 99
            assertEquals(setOf("active", "score"), data.dirtyProperties)
        }
    }

    @Test
    fun `hierarchy selective update persists only changed properties`() {
        transaction {
            val data = LeafData("charlie", true, 77)
            HierarchyTable.create(data)

            // Only change score, leave active unchanged
            data.score = 99
            HierarchyTable.update(data, HierarchyTable.score)

            val row = HierarchyTable.selectAll().where { HierarchyTable.id eq data.id }.single()
            assertEquals("charlie", row[HierarchyTable.name])
            assertEquals(true, row[HierarchyTable.active])
            assertEquals(99, row[HierarchyTable.score])
        }
    }

    @Test
    fun `explicit update keeps unspecified dirty properties dirty`() {
        transaction {
            val data = MutablePairData("delta", 1, true)
            MutablePairTable.create(data)

            data.score = 2
            data.active = false
            MutablePairTable.update(data, MutablePairTable.score)

            assertEquals(setOf("active"), data.dirtyProperties)
            assertTrue(data.isDirty)
        }
    }

    @Test
    fun `explicit update can write all requested var columns`() {
        transaction {
            val data = LeafData("charlie", true, 77)
            HierarchyTable.create(data)

            data.active = false
            data.score = 99
            HierarchyTable.update(data, HierarchyTable.active, HierarchyTable.score)

            val row = HierarchyTable.selectAll().where { HierarchyTable.id eq data.id }.single()
            assertEquals("charlie", row[HierarchyTable.name])
            assertEquals(false, row[HierarchyTable.active])
            assertEquals(99, row[HierarchyTable.score])
            assertFalse(data.isDirty)
        }
    }

    // -- dirtyPropertiesAsJson ------------------------------------------------

    @Test
    fun `dirtyPropertiesAsJson returns empty object when clean`() {
        transaction {
            val data = SimpleData("alice", 42)
            SimpleTable.create(data)
            val json = data.dirtyPropertiesAsJson()
            assertEquals(0, json.size)
        }
    }

    @Test
    fun `dirtyPropertiesAsJson contains only changed properties`() {
        transaction {
            val data = SimpleData("alice", 42)
            SimpleTable.create(data)
            data.score = 100
            val json = data.dirtyPropertiesAsJson()
            assertEquals(1, json.size)
            assertEquals(100, json["score"].int)
        }
    }

    @Test
    fun `dirtyPropertiesAsJson with hierarchy includes dirty from all levels`() {
        transaction {
            val data = LeafData("charlie", true, 77)
            HierarchyTable.create(data)
            data.active = false
            data.score = 99
            val json = data.dirtyPropertiesAsJson()
            assertEquals(2, json.size)
            assertEquals(false, json["active"].boolean)
            assertEquals(99, json["score"].int)
        }
    }

    @Test
    fun `dirtyPropertiesAsJson on new object without snapshot returns empty`() {
        val data = SimpleData("alice", 42)
        val json = data.dirtyPropertiesAsJson()
        assertEquals(0, json.size)
    }

    // -- unlinkFromDB clears snapshot -----------------------------------------

    @Test
    fun `delete clears snapshot and object becomes dirty again`() {
        transaction {
            val data = SimpleData("alice", 42)
            SimpleTable.create(data)
            assertFalse(data.isDirty)

            SimpleTable.delete(data)
            assertTrue(data.isDirty)
        }
    }
}
