package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.HierarchyTable
import org.tekfive.keep.data.LeafData
import org.tekfive.keep.data.NoteData
import org.tekfive.keep.data.NoteTable
import org.tekfive.keep.data.Priority
import org.tekfive.keep.data.SimpleData
import org.tekfive.keep.data.SimpleTable
import org.tekfive.keep.data.SimpleWidgetJoin
import org.tekfive.keep.data.TaskData
import org.tekfive.keep.data.TaskTable
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.data.WidgetData
import org.tekfive.keep.data.WidgetTable
import org.tekfive.keep.text.citext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private object TestSchema : AppSchema() {
    override val tables = listOf(SimpleTable, HierarchyTable)
}

private object CitextTable : Table("citext_schema_test") {
    val value = citext("value")
}

private object ExtSchema : AppSchema() {
    override val extensions = listOf("citext")
    override val tables = listOf(CitextTable)
}

private object ForeignKeySchema : AppSchema() {
    override val tables = listOf(SimpleTable, NoteTable)
}

private object JoinTableSchema : AppSchema() {
    override val tables = listOf(SimpleTable, WidgetTable, SimpleWidgetJoin)
}

private object EnumSchema : AppSchema() {
    override val tables = listOf(TaskTable)
}

class AppSchemaTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
    }

    @AfterTest
    fun teardown() {
        // Drop all schemas that tests may have created, ignoring errors for tables that don't exist
        runCatching { transaction { JoinTableSchema.drop() } }
        runCatching { transaction { ForeignKeySchema.drop() } }
        runCatching { transaction { EnumSchema.drop() } }
        runCatching { transaction { TestSchema.drop() } }
    }

    @Test
    fun `create builds all tables`() {
        transaction {
            TestSchema.create()

            val data = SimpleData("alice", 42)
            SimpleTable.create(data)

            val row = SimpleTable.selectAll().where { SimpleTable.id eq data.id }.single()
            assertEquals("alice", row[SimpleTable.name])
        }
    }

    @Test
    fun `drop removes all tables`() {
        transaction {
            TestSchema.create()
            SimpleTable.create(SimpleData("alice", 42))
            TestSchema.drop()
            TestSchema.create()

            // Tables should be empty after drop + re-create
            assertTrue(SimpleTable.selectAll().empty())
        }
    }

    @Test
    fun `tables list is accessible`() {
        assertEquals(2, TestSchema.tables.size)
        assertTrue(SimpleTable in TestSchema.tables)
        assertTrue(HierarchyTable in TestSchema.tables)
    }

    @Test
    fun `create and use multiple tables`() {
        transaction {
            TestSchema.create()

            SimpleTable.create(SimpleData("bob", 10))
            HierarchyTable.create(LeafData("charlie", true, 77))

            assertEquals(1, SimpleTable.selectAll().count())
            assertEquals(1, HierarchyTable.selectAll().count())
        }
    }

    @Test
    fun `createIfNecessary skips existing tables and preserves data`() {
        transaction {
            TestSchema.create()
            SimpleTable.create(SimpleData("alice", 42))

            // calling again should not fail or lose data
            TestSchema.createIfNecessary()

            assertEquals(1, SimpleTable.selectAll().count())
        }
    }

    @Test
    fun `create installs extensions before tables`() {
        transaction {
            ExtSchema.create()

            // citext extension was installed, so the table with a citext column works
            TransactionManager.current().exec("INSERT INTO citext_schema_test (value) VALUES ('Hello')")
            val count = CitextTable.selectAll().count()
            assertEquals(1, count)

            ExtSchema.drop()
        }
    }

    // -- Schema creation verification tests -----------------------------------

    @Test
    fun `create registers tables in database catalog`() {
        transaction {
            TestSchema.create()

            val tableNames = catalogTableNames()
            assertTrue("simple" in tableNames, "simple table should exist in catalog")
            assertTrue("hierarchy" in tableNames, "hierarchy table should exist in catalog")
        }
    }

    @Test
    fun `create builds correct columns for table`() {
        transaction {
            TestSchema.create()

            val columns = catalogColumns("simple")
            assertTrue("id" in columns, "simple table should have id column")
            assertTrue("name" in columns, "simple table should have name column")
            assertTrue("score" in columns, "simple table should have score column")
        }
    }

    @Test
    fun `create builds primary key constraint`() {
        transaction {
            TestSchema.create()

            val pkColumns = catalogPrimaryKeyColumns("simple")
            assertEquals(listOf("id"), pkColumns, "simple table primary key should be id")
        }
    }

    @Test
    fun `create with foreign key tables builds referential constraint`() {
        transaction {
            ForeignKeySchema.create()

            val simple = SimpleData("alice", 42)
            SimpleTable.create(simple)

            val note = NoteData("hello", simple.id)
            NoteTable.create(note)

            val row = NoteTable.selectAll().where { NoteTable.id eq note.id }.single()
            assertEquals("hello", row[NoteTable.text])
            assertEquals(simple.id, row[NoteTable.simpleId])

            // Verify FK exists in catalog
            val fks = catalogForeignKeys("notes")
            assertTrue(fks.any { it.first == "simple_id" && it.second == "simple" },
                "notes table should have FK from simple_id to simple table")
        }
    }

    @Test
    fun `create with join table builds composite primary key and foreign keys`() {
        transaction {
            JoinTableSchema.create()

            val simple = SimpleData("alice", 42)
            SimpleTable.create(simple)
            val widget = WidgetData("gear", 5)
            WidgetTable.create(widget)

            // Insert a join row via raw SQL
            TransactionManager.current().exec(
                "INSERT INTO simple_widget (simple_id, widgets_id) VALUES (${simple.id}, ${widget.id})"
            )

            val pkColumns = catalogPrimaryKeyColumns("simple_widget")
            assertEquals(2, pkColumns.size, "join table should have composite PK with 2 columns")

            val fks = catalogForeignKeys("simple_widget")
            assertTrue(fks.any { it.second == "simple" }, "join table should FK to simple")
            assertTrue(fks.any { it.second == "widgets" }, "join table should FK to widgets")
        }
    }

    @Test
    fun `create with enum column table stores enum ids`() {
        transaction {
            EnumSchema.create()

            val task = TaskData("fix bug", Priority.HIGH)
            TaskTable.create(task)

            val row = TaskTable.selectAll().where { TaskTable.id eq task.id }.single()
            assertEquals("fix bug", row[TaskTable.title])
            assertEquals(Priority.HIGH, row[TaskTable.priority])
        }
    }

    @Test
    fun `drop removes tables from database catalog`() {
        transaction {
            TestSchema.create()
            assertTrue("simple" in catalogTableNames())

            TestSchema.drop()
            assertFalse("simple" in catalogTableNames(), "simple table should not exist after drop")
            assertFalse("hierarchy" in catalogTableNames(), "hierarchy table should not exist after drop")
        }
    }

    @Test
    fun `empty schema creates and drops without error`() {
        val emptySchema = object : AppSchema() {
            override val tables = emptyList<Table>()
        }
        transaction {
            emptySchema.create()
            emptySchema.drop()
        }
    }

    // -- Catalog query helpers ------------------------------------------------

    private fun catalogTableNames(): Set<String> {
        val names = mutableSetOf<String>()
        TransactionManager.current().exec(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
        ) { rs ->
            while (rs.next()) {
                names += rs.getString("table_name")
            }
        }
        return names
    }

    private fun catalogColumns(tableName: String): Set<String> {
        val cols = mutableSetOf<String>()
        TransactionManager.current().exec(
            "SELECT column_name FROM information_schema.columns WHERE table_name = '$tableName'"
        ) { rs ->
            while (rs.next()) {
                cols += rs.getString("column_name")
            }
        }
        return cols
    }

    private fun catalogPrimaryKeyColumns(tableName: String): List<String> {
        val cols = mutableListOf<String>()
        TransactionManager.current().exec("""
            SELECT kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
            WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_name = '$tableName'
            ORDER BY kcu.ordinal_position
        """.trimIndent()) { rs ->
            while (rs.next()) {
                cols += rs.getString("column_name")
            }
        }
        return cols
    }

    /** Returns pairs of (column_name, referenced_table_name) for all FKs on [tableName]. */
    private fun catalogForeignKeys(tableName: String): List<Pair<String, String>> {
        val fks = mutableListOf<Pair<String, String>>()
        TransactionManager.current().exec("""
            SELECT kcu.column_name, ccu.table_name AS referenced_table
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
            JOIN information_schema.constraint_column_usage ccu
              ON tc.constraint_name = ccu.constraint_name AND tc.table_schema = ccu.table_schema
            WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = '$tableName'
        """.trimIndent()) { rs ->
            while (rs.next()) {
                fks += rs.getString("column_name") to rs.getString("referenced_table")
            }
        }
        return fks
    }
}
