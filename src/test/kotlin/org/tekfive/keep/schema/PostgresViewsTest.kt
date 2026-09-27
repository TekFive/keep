package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.union
import org.jetbrains.exposed.v1.jdbc.unionAll
import org.tekfive.keep.data.Data
import org.tekfive.keep.data.DataView
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.migration.dynamic.*
import kotlin.test.*

private const val VIEW_SCHEMA = "keep_views_test"
private object CurrentItems : Table("$VIEW_SCHEMA.current_items") {
    val id = long("id")
    val label = text("label")
}
private object ArchivedItems : Table("$VIEW_SCHEMA.archived_items") {
    val id = long("id")
    val label = text("label")
}
private class ViewItem(val label: String) : Data()
private object AllItems : DataView<ViewItem>("$VIEW_SCHEMA.all_items") {
    val label = text("label")
    override val viewDefinition = view(CurrentItems, ArchivedItems) {
        CurrentItems.select(CurrentItems.id, CurrentItems.label).unionAll(
            ArchivedItems.select(ArchivedItems.id, ArchivedItems.label),
        )
    }
}
private object VisibleItems : Table("$VIEW_SCHEMA.visible_items") {
    val id = long("id")
    val label = text("label")
}

class PostgresViewsTest {
    private lateinit var database: Database
    @BeforeTest fun setup() { database = TestDatabase.connect() }
    @AfterTest fun cleanup() { transaction(database) { exec("DROP SCHEMA IF EXISTS $VIEW_SCHEMA CASCADE") } }

    private fun schema(unionAll: Boolean = true, filtered: Boolean = false) = object : KeepSchema(VIEW_SCHEMA) {
        override val tables = listOf(CurrentItems, ArchivedItems)
        // Deliberately reversed: source references determine creation order.
        override val views = listOf(
            VisibleItems.view(AllItems) { AllItems.select(AllItems.id, AllItems.label).where { AllItems.label neq "hidden" } },
            AllItems.view(CurrentItems, ArchivedItems) {
                val current = CurrentItems.select(CurrentItems.id, CurrentItems.label)
                if (filtered) current.where { CurrentItems.label neq "excluded" }
                val archived = ArchivedItems.select(ArchivedItems.id, ArchivedItems.label)
                if (unionAll) current.unionAll(archived) else current.union(archived)
            },
        )
    }

    @Test fun `offline fresh install renders typed union views in dependency order and maps rows`() {
        val desired = schema()
        val install = PostgresFreshInstallGenerator.plan(desired)
        val views = install.statements.filter { it.startsWith("CREATE VIEW") }
        assertTrue(views[0].contains("all_items"))
        assertTrue(views[0].contains("UNION ALL"))
        assertTrue(views[1].contains("visible_items"))
        assertFalse(views.any { '?' in it })
        transaction(database) {
            install.statements.forEach { exec(it) }
            exec("INSERT INTO $VIEW_SCHEMA.current_items VALUES (1, 'same'), (2, 'hidden')")
            exec("INSERT INTO $VIEW_SCHEMA.archived_items VALUES (1, 'same')")
            assertEquals(listOf("same", "same"), VisibleItems.selectAll().map { it[VisibleItems.label] })
        }
        assertTrue(PostgresMigrationGenerator.plan(database, desired, true).isEmpty)
    }

    @Test fun `dynamic install validates unions and leaves no schema before execution`() {
        val desired = schema(unionAll = false)
        val plan = PostgresMigrationGenerator.plan(database, desired, true)
        assertEquals(2, plan.statements.filterIsInstance<CreateView>().size)
        transaction(database) { assertNull(exec("SELECT to_regclass('$VIEW_SCHEMA.all_items')") { it.next(); it.getString(1) }) }
        plan.execute(database)
        transaction(database) {
            exec("INSERT INTO $VIEW_SCHEMA.current_items VALUES (1, 'same')")
            exec("INSERT INTO $VIEW_SCHEMA.archived_items VALUES (1, 'same')")
            assertEquals(1, AllItems.selectAll().count().toInt())
        }
        assertTrue(PostgresMigrationGenerator.plan(database, desired, true).isEmpty)
    }

    @Test fun `query changes replace the definition without dropping matching structure`() {
        PostgresMigrationGenerator.plan(database, schema(), true).execute(database)
        transaction(database) { exec("INSERT INTO $VIEW_SCHEMA.current_items VALUES (1, 'excluded'), (2, 'kept')") }
        val desired = schema(filtered = true)
        val plan = PostgresMigrationGenerator.plan(database, desired, true)
        assertEquals(listOf(CreateOrReplaceView::class), plan.statements.map { it::class })
        transaction(database) { assertEquals(2L, AllItems.selectAll().count()) }
        plan.execute(database)
        transaction(database) {
            assertEquals(listOf("kept"), AllItems.selectAll().map { it[AllItems.label] })
            assertEquals(2L, CurrentItems.selectAll().count())
        }
        assertTrue(PostgresMigrationGenerator.plan(database, desired, true).isEmpty)
    }

    @Test fun `AppSchema creates and drops declared views in dependency order`() {
        val desired = schema()
        val app = object : AppSchema(VIEW_SCHEMA) {
            override val tables = desired.tables
            override val views = desired.views
            override val sequences = emptyList<String>()
        }
        transaction(database) {
            exec("CREATE SCHEMA $VIEW_SCHEMA")
            app.create()
            assertEquals(0L, AllItems.selectAll().count())
            app.drop()
            assertNull(exec("SELECT to_regclass('$VIEW_SCHEMA.all_items')") { it.next(); it.getString(1) })
        }
    }

    @Test fun `changed output shape is dropped and recreated`() {
        val table = object : Table("$VIEW_SCHEMA.items") {
            val id = integer("id")
            val label = text("label")
        }
        fun desired(full: Boolean) = object : KeepSchema(VIEW_SCHEMA) {
            override val tables = listOf(table)
            override val views = listOf(postgresView("items_view", table) {
                if (full) table.select(table.id, table.label) else table.select(table.id)
            })
        }
        PostgresMigrationGenerator.plan(database, desired(true), true).execute(database)
        val plan = PostgresMigrationGenerator.plan(database, desired(false), true)
        assertEquals(listOf(DropView::class, CreateView::class), plan.statements.map { it::class })
        plan.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, desired(false), true).isEmpty)
    }

    @Test fun `views use newly added columns without changing the table during planning`() {
        transaction(database) {
            exec("CREATE SCHEMA $VIEW_SCHEMA")
            exec("CREATE TABLE $VIEW_SCHEMA.items (id INTEGER)")
            exec("INSERT INTO $VIEW_SCHEMA.items VALUES (1)")
            exec("CREATE SEQUENCE $VIEW_SCHEMA.numbers START 100")
            exec("CREATE VIEW $VIEW_SCHEMA.items_view AS SELECT id FROM $VIEW_SCHEMA.items")
        }
        val table = object : Table("$VIEW_SCHEMA.items") {
            val id = integer("id").nullable()
            val label = text("label").nullable()
        }
        val desired = object : KeepSchema(VIEW_SCHEMA) {
            override val tables = listOf(table)
            override val sequenceNames = listOf("numbers")
            override val views = listOf(postgresView("items_view", table) { table.select(table.id, table.label) })
        }
        val plan = PostgresMigrationGenerator.plan(database, desired, true)
        assertTrue(plan.statements.any { it is AddColumn })
        transaction(database) {
            assertEquals(1, exec("SELECT count(*) FROM information_schema.columns WHERE table_schema = '$VIEW_SCHEMA' AND table_name = 'items'") { it.next(); it.getInt(1) })
            assertFalse(exec("SELECT is_called FROM $VIEW_SCHEMA.numbers") { it.next(); it.getBoolean(1) }!!)
        }
        plan.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, desired, true).isEmpty)
    }

    @Test fun `column type changes drop blocking views before the table alteration`() {
        transaction(database) {
            exec("CREATE SCHEMA $VIEW_SCHEMA")
            exec("CREATE TABLE $VIEW_SCHEMA.items (label VARCHAR(10) NOT NULL)")
            exec("INSERT INTO $VIEW_SCHEMA.items VALUES ('hello')")
            exec("CREATE VIEW $VIEW_SCHEMA.items_view AS SELECT label FROM $VIEW_SCHEMA.items")
        }
        val table = object : Table("$VIEW_SCHEMA.items") { val label = text("label") }
        val desired = object : KeepSchema(VIEW_SCHEMA) {
            override val tables = listOf(table)
            override val views = listOf(postgresView("items_view", table) { table.select(table.label) })
        }
        val plan = PostgresMigrationGenerator.plan(database, desired, false)
        assertEquals(listOf(DropView::class, AlterColumnType::class, CreateView::class), plan.statements.map { it::class })
        plan.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, desired, false).isEmpty)
    }

    @Test fun `query formatting is ignored but spaces inside literals remain significant`() {
        fun desired(query: String) = object : KeepSchema(VIEW_SCHEMA) {
            override val tables = emptyList<Table>()
            override val views = listOf(PostgresViewDefinition("literal_view", query))
        }
        PostgresMigrationGenerator.plan(database, desired("SELECT 'a  b'::text AS label"), true).execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, desired(" select  ('a  b'::text) as label; "), true).isEmpty)
        val changed = PostgresMigrationGenerator.plan(database, desired("SELECT 'a b'::text AS label"), true)
        assertIs<CreateOrReplaceView>(changed.statements.single())
    }

    @Test fun `invalid queries roll back all simulated views and retain caller writes`() {
        PostgresMigrationGenerator.plan(database, schema(), true).execute(database)
        transaction(database) {
            exec("INSERT INTO $VIEW_SCHEMA.current_items VALUES (1, 'saved')")
            val bad = object : KeepSchema(VIEW_SCHEMA) {
                override val tables = schema().tables
                override val views = listOf(
                    schema(filtered = true).views[1],
                    PostgresViewDefinition("visible_items", "SELECT missing_column FROM $VIEW_SCHEMA.all_items"),
                )
            }
            assertFails { PostgresMigrationGenerator.plan(bad, true) }
            assertEquals(1L, VisibleItems.selectAll().count())
            assertEquals(1L, CurrentItems.selectAll().count())
        }
        assertTrue(PostgresMigrationGenerator.plan(database, schema(), true).isEmpty)
    }

    @Test fun `undeclared sources and cyclic view references are rejected offline`() {
        val missing = object : KeepSchema(VIEW_SCHEMA) {
            override val tables = emptyList<Table>()
            override val views = listOf(AllItems.view(CurrentItems) { CurrentItems.selectAll() })
        }
        assertFailsWith<IllegalArgumentException> { PostgresFreshInstallGenerator.plan(missing) }
        val cyclic = object : KeepSchema(VIEW_SCHEMA) {
            override val tables = emptyList<Table>()
            override val views = listOf(
                AllItems.view(VisibleItems) { VisibleItems.selectAll() },
                VisibleItems.view(AllItems) { AllItems.selectAll() },
            )
        }
        assertFailsWith<IllegalArgumentException> { PostgresFreshInstallGenerator.plan(cyclic) }
    }

    @Test fun `shape changes rebuild dependent views in dependency order`() {
        val original = schema()
        PostgresMigrationGenerator.plan(database, original, true).execute(database)
        val desired = object : KeepSchema(VIEW_SCHEMA) {
            override val tables = original.tables
            override val views = listOf(
                VisibleItems.view(AllItems) { AllItems.select(AllItems.id) },
                AllItems.view(CurrentItems, ArchivedItems) {
                    CurrentItems.select(CurrentItems.id).unionAll(ArchivedItems.select(ArchivedItems.id))
                },
            )
        }
        val plan = PostgresMigrationGenerator.plan(database, desired, true)
        assertEquals(listOf("visible_items", "all_items"), plan.statements.filterIsInstance<DropView>().map { it.name.name })
        assertEquals(listOf("all_items", "visible_items"), plan.statements.filterIsInstance<CreateView>().map { it.name.name })
        plan.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, desired, true).isEmpty)
    }

    @Test fun `materialized dependent blocks the entire structural replacement in non-destructive mode`() {
        val source = object : Table("$VIEW_SCHEMA.base_view") { val id = long("id") }
        fun desired(full: Boolean) = object : KeepSchema(VIEW_SCHEMA) {
            override val tables = listOf(CurrentItems)
            override val views = listOf(
                postgresView("snapshot", source, materialized = true) { source.select(source.id) },
                source.view(CurrentItems) {
                    if (full) CurrentItems.select(CurrentItems.id, CurrentItems.label) else CurrentItems.select(CurrentItems.id)
                },
            )
        }
        PostgresMigrationGenerator.plan(database, desired(true), true).execute(database)
        val safe = PostgresMigrationGenerator.plan(database, desired(false), true)
        assertTrue(safe.statements.isEmpty())
        assertEquals(4, safe.suppressedStatements.size)
        assertTrue(safe.suppressedStatements.all { it.reason == DestructivePostgresMigrationChange.DROP_MATERIALIZED_VIEW })
        val destructive = PostgresMigrationGenerator.plan(database, desired(false), false)
        destructive.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, desired(false), false).isEmpty)
    }

    @Test fun `external dependents prevent structural replacement and rollback preserves the transaction`() {
        PostgresMigrationGenerator.plan(database, schema(), true).execute(database)
        transaction(database) {
            exec("CREATE TEMP VIEW external_view AS SELECT * FROM $VIEW_SCHEMA.all_items")
            val desired = object : KeepSchema(VIEW_SCHEMA) {
                override val tables = schema().tables
                override val views = listOf(AllItems.view(CurrentItems) { CurrentItems.select(CurrentItems.id) })
            }
            assertFails { PostgresMigrationGenerator.plan(desired, false) }
            assertEquals(0L, AllItems.selectAll().count())
            exec("SELECT * FROM external_view") { assertFalse(it.next()) }
        }
    }

    @Test fun `all union sources must be explicitly referenced`() {
        val desired = object : KeepSchema(VIEW_SCHEMA) {
            override val tables = listOf(CurrentItems, ArchivedItems)
            override val views = listOf(AllItems.view(CurrentItems) {
                CurrentItems.select(CurrentItems.id).unionAll(ArchivedItems.select(ArchivedItems.id))
            })
        }
        assertFailsWith<IllegalArgumentException> { PostgresFreshInstallGenerator.plan(desired) }
    }

    @Test fun `removed dependent views are dropped before their sources`() {
        PostgresMigrationGenerator.plan(database, schema(), true).execute(database)
        val desired = object : KeepSchema(VIEW_SCHEMA) { override val tables = schema().tables }
        val plan = PostgresMigrationGenerator.plan(database, desired, false)
        assertEquals(listOf("visible_items", "all_items"), plan.statements.filterIsInstance<DropView>().map { it.name.name })
        plan.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, desired, false).isEmpty)
    }
}
