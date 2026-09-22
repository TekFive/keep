package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ColumnRenamesTest {
    @Test
    fun `historical names accumulate without duplicates and survive column copies`() {
        val table = object : Table("rename_metadata") {
            val label = text("label").renamedFrom("name", "full_name").nullable()
                .renamedFrom("name", "public_name")
            val count = integer("count").renamedFrom("quantity")
                .transform({ it.toString() }, { it.toInt() })
        }
        assertEquals(listOf("name", "full_name", "public_name"), table.label.previousNames)
        assertEquals(listOf("quantity"), table.count.previousNames)
        assertFailsWith<UnsupportedOperationException> {
            (table.label.previousNames as MutableList<String>).add("unexpected")
        }
    }

    @Test
    fun `metadata is isolated by table identity and works for property columns`() {
        val first = object : Table("same_name") {
            val label = text("label").renamedFrom("old_name")
        }
        val second = object : Table("same_name") {
            val label = text("label")
        }
        val dataTable = object : DataTable<RenamedData>("renamed_data") {
            val label = column(RenamedData::label).renamedFrom("old_label")
        }
        assertEquals(listOf("old_name"), first.label.previousNames)
        assertEquals(emptyList(), second.label.previousNames)
        assertEquals(listOf("old_label"), dataTable.label.previousNames)
        assertEquals(RenamedData::label, dataTable.label.dataProperty)
    }

    @Test
    fun `invalid names fail at declaration`() {
        val column = Table("invalid_names").text("label")
        assertFailsWith<IllegalArgumentException> { column.renamedFrom() }
        assertFailsWith<IllegalArgumentException> { column.renamedFrom("") }
        assertFailsWith<IllegalArgumentException> { column.renamedFrom("label") }
        assertFailsWith<IllegalArgumentException> { column.renamedFrom("bad\u0000name") }
        assertFailsWith<IllegalArgumentException> { column.renamedFrom("é".repeat(32)) }
    }
}

private class RenamedData(val label: String) : Data()
