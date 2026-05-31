package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.tekfive.keep.utils.ColumnValueMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** In-memory [ColumnValueMapper] that captures column-value pairs for assertions. */
class TestColumnValueMapper : ColumnValueMapper {
    val values = mutableMapOf<Column<*>, Any?>()
    override val updatedColumns: List<Column<*>> get() = values.keys.toList()
    override fun <S> set(column: Column<S>, value: S) {
        values[column] = value
    }
}

class DataTableTest {

    // -- Init validation tests ------------------------------------------------

    @Test
    fun `simple table constructs successfully`() {
        // SimpleTable is an object, so accessing it triggers construction.
        // If the init validation fails, this would throw.
        assertTrue(SimpleTable.columns.any { it.name == "name" })
    }

    @Test
    fun `hierarchy table constructs successfully`() {
        assertTrue(HierarchyTable.columns.any { it.name == "name" })
    }

    @Test
    fun `intermediate abstract table constructs successfully`() {
        assertTrue(WidgetTable.columns.any { it.name == "label" })
        assertTrue(WidgetTable.columns.any { it.name == "quantity" })
    }

    @Test
    fun `init fails when column has no matching data property`() {
        assertFailsWith<IllegalStateException> {
            object : DataTable<SimpleData>("bad_extra_col") {
                val name = varchar("name", 255)
                val score = integer("score")
                val extra = varchar("extra", 255) // no matching property on SimpleData
            }
        }
    }

    @Test
    fun `init fails when data property has no matching column`() {
        assertFailsWith<IllegalStateException> {
            object : DataTable<MissingColData>("bad_missing_col") {
                val a = varchar("a", 255)
                // missing column for property 'b'
            }
        }
    }

    // -- mapColumns tests ---------------------------------------------

    @Test
    fun `simple insert maps all properties`() {
        val data = SimpleData("alice", 42)
        val mapper = TestColumnValueMapper()

        SimpleTable.mapColumns(data, mapper, insert = true)

        assertEquals("alice", mapper.values[SimpleTable.name])
        assertEquals(42, mapper.values[SimpleTable.score])
    }

    @Test
    fun `simple update maps only var properties`() {
        val data = SimpleData("alice", 42)
        val mapper = TestColumnValueMapper()

        SimpleTable.mapColumns(data, mapper, insert = false)

        // 'name' is val, should NOT be mapped on update
        assertTrue(SimpleTable.name !in mapper.values)
        // 'score' is var, should be mapped
        assertEquals(42, mapper.values[SimpleTable.score])
    }

    @Test
    fun `hierarchy insert maps properties from all levels`() {
        val data = LeafData("bob", true, 99)
        val mapper = TestColumnValueMapper()

        HierarchyTable.mapColumns(data, mapper, insert = true)

        // val from BaseData
        assertEquals("bob", mapper.values[HierarchyTable.name])
        // var from BaseData
        assertEquals(true, mapper.values[HierarchyTable.active])
        // var from LeafData
        assertEquals(99, mapper.values[HierarchyTable.score])
    }

    @Test
    fun `hierarchy update maps only var properties from all levels`() {
        val data = LeafData("bob", false, 50)
        val mapper = TestColumnValueMapper()

        HierarchyTable.mapColumns(data, mapper, insert = false)

        // 'name' is val (from BaseData), should NOT be mapped on update
        assertTrue(HierarchyTable.name !in mapper.values)
        // 'active' is var (from BaseData), should be mapped
        assertEquals(false, mapper.values[HierarchyTable.active])
        // 'score' is var (from LeafData), should be mapped
        assertEquals(50, mapper.values[HierarchyTable.score])
    }

    @Test
    fun `intermediate table insert maps properties from split column definitions`() {
        val data = WidgetData("sprocket", 10)
        val mapper = TestColumnValueMapper()

        WidgetTable.mapColumns(data, mapper, insert = true)

        // val 'label' — column defined in BaseWidgetTable, property in WidgetData
        assertEquals("sprocket", mapper.values[WidgetTable.label])
        // var 'quantity' — column defined in WidgetTable, property in WidgetData
        assertEquals(10, mapper.values[WidgetTable.quantity])
    }

    @Test
    fun `intermediate table update maps only var properties`() {
        val data = WidgetData("sprocket", 10)
        val mapper = TestColumnValueMapper()

        WidgetTable.mapColumns(data, mapper, insert = false)

        assertTrue(WidgetTable.label !in mapper.values)
        assertEquals(10, mapper.values[WidgetTable.quantity])
    }

    @Test
    fun `update reflects mutated var values`() {
        val data = SimpleData("alice", 42)
        data.score = 100

        val mapper = TestColumnValueMapper()
        SimpleTable.mapColumns(data, mapper, insert = false)

        assertEquals(100, mapper.values[SimpleTable.score])
    }

    // -- fkey tests -----------------------------------------------------------

    @Test
    fun `fkey column references target table id`() {
        val fk = checkNotNull(NoteTable.simpleId.foreignKey) { "Foreign key constraint should exist" }
        assertEquals(SimpleTable.id, fk.targetOf(NoteTable.simpleId))
    }

    @Test
    fun `fkey constraint has expected name`() {
        val fk = checkNotNull(NoteTable.simpleId.foreignKey) { "Foreign key constraint should exist" }
        assertEquals("notes_simple_id_fk", fk.customFkName)
    }

    // -- empty list → null on nullable column tests ----------------------------

    @Test
    fun `empty list maps to null on nullable column`() {
        val data = NullableListData("alice", emptyList())
        val mapper = TestColumnValueMapper()

        NullableListTable.mapColumns(data, mapper, insert = true)

        assertEquals("alice", mapper.values[NullableListTable.name])
        assertEquals(null, mapper.values[NullableListTable.tags])
    }

    @Test
    fun `non-empty list maps normally on nullable column`() {
        val data = NullableListData("bob", listOf("a", "b"))
        val mapper = TestColumnValueMapper()

        NullableListTable.mapColumns(data, mapper, insert = true)

        assertEquals(listOf("a", "b"), mapper.values[NullableListTable.tags])
    }

    @Test
    fun `empty list on non-nullable column maps as empty list`() {
        val data = TaggedData("alice", emptyList())
        val mapper = TestColumnValueMapper()

        TaggedTable.mapColumns(data, mapper, insert = true)

        assertEquals(emptyList<Any>(), mapper.values[TaggedTable.priorities])
    }

    @Test
    fun `fkey column is indexed`() {
        val indexed = NoteTable.indices.any { index ->
            index.columns.singleOrNull() == NoteTable.simpleId
        }
        assertTrue(indexed, "fkey column should be indexed")
    }
}
