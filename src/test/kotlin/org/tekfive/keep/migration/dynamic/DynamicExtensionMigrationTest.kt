package org.tekfive.keep.migration.dynamic

import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.data.renamedFrom
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresViewDefinition
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val EXTENSION_SCHEMA = "keep_dynamic_extensions_test"

class DynamicExtensionMigrationTest {
    private lateinit var database: Database

    @BeforeTest fun setup() {
        database = TestDatabase.connect()
        transaction(database) {
            assertFalse(installed(KeepSchema.HSTORE))
            exec("CREATE SCHEMA $EXTENSION_SCHEMA")
        }
    }

    @AfterTest fun cleanup() {
        transaction(database) {
            exec("DROP EXTENSION IF EXISTS hstore CASCADE")
            exec("DROP SCHEMA IF EXISTS $EXTENSION_SCHEMA CASCADE")
        }
    }

    @Test fun `non-destructive migration adds extensions before dependent columns and views`() = verifyDependencies(true)

    @Test fun `destructive migration adds extensions before dependent columns and views`() = verifyDependencies(false)

    private fun verifyDependencies(nonDestructive: Boolean) {
        val records = object : Table("$EXTENSION_SCHEMA.records") {
            val id = integer("id").renamedFrom("old_id")
            val attributes = registerColumn<String>("attributes", object : ColumnType<String>() {
                override fun sqlType() = "hstore"
                override fun valueFromDB(value: Any) = value.toString()
            }).nullable()
        }
        val schema = object : KeepSchema(EXTENSION_SCHEMA) {
            override val tables = listOf(records)
            override val extensions = listOf(HSTORE, HSTORE, "plpgsql")
            override val views = listOf(PostgresViewDefinition("labels", "SELECT 'key=>value'::hstore AS attributes"))
        }
        transaction(database) {
            exec("CREATE TABLE $EXTENSION_SCHEMA.records (old_id INTEGER NOT NULL)")
            exec("INSERT INTO $EXTENSION_SCHEMA.records VALUES (1)")
            val plan = PostgresMigrationGenerator.plan(schema, nonDestructive)
            assertEquals(listOf(CreateExtension(KeepSchema.HSTORE)), plan.statements.filterIsInstance<CreateExtension>())
            assertIs<CreateExtension>(plan.statements.first())
            assertIs<RenameColumn>(plan.statements[1])
            assertTrue(plan.suppressedStatements.isEmpty())
            assertFalse(installed(KeepSchema.HSTORE))
            exec("INSERT INTO $EXTENSION_SCHEMA.records VALUES (2)")
            assertEquals(2, exec("SELECT count(old_id) FROM $EXTENSION_SCHEMA.records") { it.next(); it.getInt(1) })
            assertEquals(plan, PostgresMigrationGenerator.plan(schema, nonDestructive))
            plan.execute()
            assertTrue(installed(KeepSchema.HSTORE))
            exec("INSERT INTO $EXTENSION_SCHEMA.records (id, attributes) VALUES (3, 'key=>value')")
            assertEquals("value", exec("SELECT attributes -> 'key' FROM $EXTENSION_SCHEMA.labels") { it.next(); it.getString(1) })
            assertTrue(PostgresMigrationGenerator.plan(schema, nonDestructive).isEmpty)
        }
    }

    @Test fun `extension installation failure rolls back earlier extensions and preserves caller writes`() {
        val schema = object : KeepSchema(EXTENSION_SCHEMA) {
            override val tables = emptyList<Table>()
            override val extensions = listOf(HSTORE, "keep_missing_extension")
        }
        transaction(database) {
            exec("CREATE TABLE $EXTENSION_SCHEMA.audit (id INTEGER)")
            exec("INSERT INTO $EXTENSION_SCHEMA.audit VALUES (1)")
            assertFailsWith<SQLException> { PostgresMigrationGenerator.plan(schema, true) }
            assertFalse(installed(KeepSchema.HSTORE))
            exec("INSERT INTO $EXTENSION_SCHEMA.audit VALUES (2)")
            assertEquals(2, exec("SELECT count(*) FROM $EXTENSION_SCHEMA.audit") { it.next(); it.getInt(1) })
        }
    }

    @Test fun `extension-only schema adds missing extensions and keeps undeclared extensions and their objects`() {
        val schema = object : KeepSchema(EXTENSION_SCHEMA) {
            override val tables = emptyList<Table>()
            override val extensions = listOf(HSTORE)
        }
        transaction(database) { exec("DROP SCHEMA $EXTENSION_SCHEMA") }
        val plan = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(listOf(CreateExtension(KeepSchema.HSTORE), CreateSchema(EXTENSION_SCHEMA)), plan.statements)
        transaction(database) { assertFalse(installed(KeepSchema.HSTORE)) }
        plan.execute(database)
        transaction(database) {
            // The extension lives in public, but its installation is recognized database-wide.
            exec("CREATE TABLE $EXTENSION_SCHEMA.extension_table (id INTEGER)")
            exec("CREATE VIEW $EXTENSION_SCHEMA.extension_view AS SELECT id FROM $EXTENSION_SCHEMA.extension_table")
            exec("CREATE SEQUENCE $EXTENSION_SCHEMA.extension_sequence")
            exec("ALTER EXTENSION hstore ADD TABLE $EXTENSION_SCHEMA.extension_table")
            exec("ALTER EXTENSION hstore ADD VIEW $EXTENSION_SCHEMA.extension_view")
            exec("ALTER EXTENSION hstore ADD SEQUENCE $EXTENSION_SCHEMA.extension_sequence")
        }
        assertTrue(PostgresMigrationGenerator.plan(database, schema, false).isEmpty)
        val noExtensions = object : KeepSchema(EXTENSION_SCHEMA) { override val tables = emptyList<Table>() }
        assertTrue(PostgresMigrationGenerator.plan(database, noExtensions, false).isEmpty)
    }

    private fun installed(name: String): Boolean = TransactionManager.current()
        .exec("SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = '$name')") { it.next(); it.getBoolean(1) } == true
}
