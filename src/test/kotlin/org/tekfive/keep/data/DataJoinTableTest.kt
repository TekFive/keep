package org.tekfive.keep.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataJoinTableTest {

    // -- Structure tests ------------------------------------------------------

    @Test
    fun `join table has exactly three columns (aId, bId, and no extras)`() {
        // aId + bId = 2 columns; no id column of its own
        assertEquals(2, SimpleWidgetJoin.columns.size)
    }

    @Test
    fun `default column names derived from referenced table names`() {
        val columnNames = SimpleWidgetJoin.columns.map { it.name }
        assertTrue("simple_id" in columnNames)
        assertTrue("widgets_id" in columnNames)
    }

    @Test
    fun `custom column names are used when provided`() {
        val columnNames = CustomColumnJoin.columns.map { it.name }
        assertTrue("sid" in columnNames)
        assertTrue("wid" in columnNames)
    }

    @Test
    fun `composite primary key spans both columns`() {
        val pk = SimpleWidgetJoin.primaryKey
        assertNotNull(pk)
        assertEquals(2, pk.columns.size)
        assertTrue(SimpleWidgetJoin.aId in pk.columns)
        assertTrue(SimpleWidgetJoin.bId in pk.columns)
    }

    @Test
    fun `tableA and tableB references are correct`() {
        assertEquals(SimpleTable, SimpleWidgetJoin.tableA)
        assertEquals(WidgetTable, SimpleWidgetJoin.tableB)
    }

    // -- Join helpers ---------------------------------------------------------

    @Test
    fun `joinA includes tableA columns`() {
        val join = SimpleWidgetJoin.joinA()
        val allColumns = join.columns.toSet()
        // Should contain columns from both the join table and tableA
        assertTrue(SimpleWidgetJoin.aId in allColumns)
        assertTrue(SimpleWidgetJoin.bId in allColumns)
        assertTrue(SimpleTable.id in allColumns)
        assertTrue(SimpleTable.name in allColumns)
    }

    @Test
    fun `joinB includes tableB columns`() {
        val join = SimpleWidgetJoin.joinB()
        val allColumns = join.columns.toSet()
        assertTrue(SimpleWidgetJoin.aId in allColumns)
        assertTrue(SimpleWidgetJoin.bId in allColumns)
        assertTrue(WidgetTable.id in allColumns)
        assertTrue(WidgetTable.label in allColumns)
    }

    @Test
    fun `joinBoth includes columns from both tables`() {
        val join = SimpleWidgetJoin.joinBoth()
        val allColumns = join.columns.toSet()
        // Join table columns
        assertTrue(SimpleWidgetJoin.aId in allColumns)
        assertTrue(SimpleWidgetJoin.bId in allColumns)
        // Table A columns
        assertTrue(SimpleTable.id in allColumns)
        assertTrue(SimpleTable.name in allColumns)
        // Table B columns
        assertTrue(WidgetTable.id in allColumns)
        assertTrue(WidgetTable.label in allColumns)
    }
}
