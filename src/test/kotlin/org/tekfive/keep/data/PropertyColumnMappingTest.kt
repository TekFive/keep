package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.greaterEq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

open class PropertyMappedBaseData(val label: String) : Data()
class PropertyMappedData(label: String, var count: Int, var comment: String?, var active: Boolean) :
    PropertyMappedBaseData(label)

abstract class PropertyMappedBaseTable : DataTable<PropertyMappedData>("property_mapped_data") {
    val labelColumn = column(PropertyMappedBaseData::label, name = "stored_label").uniqueIndex()
}

object PropertyMappedTable : PropertyMappedBaseTable() {
    // Deliberately swap names to prove the supplied properties win over table property names.
    val comment = column(PropertyMappedData::count).default(0).check { it greaterEq 0 }
    @JvmField
    val count = column(PropertyMappedData::comment).index()
    val active = bool("active") // Legacy declarations can coexist with property references.
}

class PropertyColumnMappingTest {
    @Test
    fun `maps renamed inherited and JvmField columns using their retained properties`() {
        val data = PropertyMappedData("label", 4, "note", true)
        val mapper = TestColumnValueMapper()
        PropertyMappedTable.mapColumns(data, mapper, insert = true)
        assertEquals("label", mapper.values[PropertyMappedTable.labelColumn])
        assertEquals(4, mapper.values[PropertyMappedTable.comment])
        assertEquals("note", mapper.values[PropertyMappedTable.count])
        assertEquals(true, mapper.values[PropertyMappedTable.active])

        val row = ResultRow.createAndFillValues(buildMap {
            putAll(mapper.values)
            put(PropertyMappedTable.id, 1L)
        })
        val loaded = PropertyMappedTable.map(row)
        assertEquals("label", loaded.label)
        assertEquals(4, loaded.count)
        assertEquals("note", loaded.comment)
        assertTrue(loaded.active)
        assertFalse(loaded.isDirty)

        val updates = TestColumnValueMapper()
        PropertyMappedTable.mapColumns(data, updates, insert = false)
        assertFalse(PropertyMappedTable.labelColumn in updates.values)
        assertEquals(4, updates.values[PropertyMappedTable.comment])
        assertEquals(PropertyMappedBaseData::label, PropertyMappedTable.labelColumn.dataProperty)
        assertEquals(PropertyMappedData::comment, PropertyMappedTable.count.dataProperty)
        assertNull(PropertyMappedTable.active.dataProperty)
    }

    @Test
    fun `retains property metadata through nullable and transform column copies`() {
        val table = object : Table("property_mapping_modifiers") {
            val renamed = column(PropertyMappedData::label).nullable().default(null).index()
            val transformed = column(PropertyMappedData::count).transform({ it }, { it })
        }
        assertEquals(PropertyMappedData::label, table.renamed.dataProperty)
        assertEquals(PropertyMappedData::count, table.transformed.dataProperty)
    }

    @Test
    fun `metadata is isolated between table instances with the same SQL name`() {
        val first = object : Table("same_name") {}
        val second = object : Table("same_name") {}
        val firstColumn = first.column(PropertyMappedData::label, name = "value")
        val secondColumn = second.column(PropertyMappedData::comment, name = "value")
        assertEquals(PropertyMappedData::label, firstColumn.dataProperty)
        assertEquals(PropertyMappedData::comment, secondColumn.dataProperty)
    }

    @Test
    fun `rejects multiple columns mapped to the same data property`() {
        val table = object : DataTable<SimpleData>("duplicate_property_mapping") {
            val first = column(SimpleData::name, name = "first")
            val second = column(SimpleData::name, name = "second")
            val score = column(SimpleData::score)
        }
        val error = assertFailsWith<IllegalStateException> { table.validateMapping() }
        assertTrue(error.message.orEmpty().contains("Multiple columns"))
    }

    @Test
    fun `rejects a property from an unrelated data class even when its name matches`() {
        val table = object : DataTable<SimpleData>("wrong_property_owner") {
            val nameColumn = column(UuidSimpleData::name)
            val scoreColumn = column(SimpleData::score)
        }
        val error = assertFailsWith<IllegalStateException> {
            table.mapColumns(SimpleData("name", 1), TestColumnValueMapper(), insert = true)
        }
        assertTrue(error.message.orEmpty().contains("does not belong to SimpleData"))
    }

    @Test
    fun `maps bound columns registered without table properties`() {
        val table = object : DataTable<SimpleData>("inline_property_mapping") {
            init {
                column(SimpleData::name)
                column(SimpleData::score)
            }
        }
        val mapper = TestColumnValueMapper()
        table.mapColumns(SimpleData("name", 5), mapper, insert = true)
        assertEquals(mapOf("name" to "name", "score" to 5), mapper.values.mapKeys { it.key.name })
    }
}
