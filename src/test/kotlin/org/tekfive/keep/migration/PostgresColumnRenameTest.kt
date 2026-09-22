package org.tekfive.keep.migration

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.io.TempDir
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.data.renamedFrom
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresFreshInstallGenerator
import org.tekfive.keep.schema.PostgresViewDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val RENAME_SCHEMA = "keep_column_rename_test"

class PostgresColumnRenameTest {
    private lateinit var database: Database
    private val users = object : Table("$RENAME_SCHEMA.users") {
        val id = integer("id")
        val displayName = text("display_name").renamedFrom("name", "full_name", "public_name")
        override val primaryKey = PrimaryKey(id)
    }
    private val schema = schema(users)

    @TempDir
    lateinit var outputDirectory: Path

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) { exec("CREATE SCHEMA $RENAME_SCHEMA") }
    }

    @AfterTest
    fun teardown() {
        transaction(database) { exec("DROP SCHEMA $RENAME_SCHEMA CASCADE") }
    }

    @Test
    fun `all historical names upgrade directly preserving data and planning leaves names unchanged`() {
        for (previous in listOf("name", "full_name", "public_name")) {
            transaction(database) {
                exec("CREATE TABLE $RENAME_SCHEMA.users (id INTEGER PRIMARY KEY, $previous TEXT NOT NULL)")
                exec("INSERT INTO $RENAME_SCHEMA.users VALUES (1, 'Ada')")
            }
            val first = PostgresMigrationGenerator.plan(database, schema, nonDestructive = true)
            assertEquals(1, first.statements.size)
            assertTrue(first.statements.single().contains("RENAME COLUMN \"$previous\" TO \"display_name\""))
            assertTrue(first.suppressedStatements.isEmpty())
            transaction(database) {
                assertEquals("Ada", exec("SELECT $previous FROM $RENAME_SCHEMA.users") { result ->
                    check(result.next())
                    result.getString(1)
                })
            }
            assertEquals(first, PostgresMigrationGenerator.plan(database, schema, nonDestructive = true))
            apply(first)
            transaction(database) {
                assertEquals("Ada", exec("SELECT display_name FROM $RENAME_SCHEMA.users") { result ->
                    check(result.next())
                    result.getString(1)
                })
            }
            val second = PostgresMigrationGenerator.plan(database, schema, nonDestructive = true)
            assertTrue(second.isEmpty, second.toSql())
            transaction(database) { exec("DROP TABLE $RENAME_SCHEMA.users") }
        }
    }

    @Test
    fun `fresh installation and missing columns create only the current name`() {
        val fresh = PostgresFreshInstallGenerator.plan(schema).toSql()
        assertTrue(fresh.contains("display_name"))
        assertFalse(fresh.contains("RENAME COLUMN"))
        assertFalse(fresh.contains("full_name"))
        val plan = PostgresMigrationGenerator.plan(database, schema, nonDestructive = true)
        apply(plan)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        transaction(database) { exec("ALTER TABLE $RENAME_SCHEMA.users DROP COLUMN display_name") }
        val missing = PostgresMigrationGenerator.plan(database, schema, nonDestructive = true)
        assertTrue(missing.statements.any { it.contains("ADD") && it.contains("display_name") })
        apply(missing)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
    }

    @Test
    fun `rename and type changes share one plan and retain safety filtering`() {
        val numbers = object : Table("$RENAME_SCHEMA.numbers") {
            val amount = long("amount").renamedFrom("quantity")
        }
        val desired = schema(numbers)
        transaction(database) {
            exec("CREATE TABLE $RENAME_SCHEMA.numbers (quantity INTEGER NOT NULL)")
            exec("INSERT INTO $RENAME_SCHEMA.numbers VALUES (42)")
        }
        val safe = PostgresMigrationGenerator.plan(database, desired, nonDestructive = true)
        assertTrue(safe.suppressedStatements.any { it.reason == DestructivePostgresMigrationChange.ALTER_COLUMN_TYPE })
        assertTrue(safe.statements.first().contains("RENAME COLUMN"))
        val complete = PostgresMigrationGenerator.plan(database, desired, nonDestructive = false)
        assertTrue(complete.statements.first().contains("RENAME COLUMN"))
        assertTrue(complete.statements.drop(1).any { it.contains(" TYPE ") })
        apply(complete)
        assertTrue(PostgresMigrationGenerator.plan(database, desired, false).isEmpty)
        transaction(database) {
            assertEquals(42L, exec("SELECT amount FROM $RENAME_SCHEMA.numbers") { result ->
                check(result.next())
                result.getLong(1)
            })
        }
    }

    @Test
    fun `views indexes and foreign keys remain usable across a rename`() {
        val parents = object : Table("$RENAME_SCHEMA.parents") {
            val key = integer("parent_key").renamedFrom("id")
            override val primaryKey = PrimaryKey(key)
        }
        val children = object : Table("$RENAME_SCHEMA.children") {
            val parent = integer("parent").references(parents.key)
            init { index("children_parent_idx", false, parent) }
        }
        val desired = schema(
            parents, children,
            views = listOf(PostgresViewDefinition("parent_view", "SELECT parent_key AS id FROM $RENAME_SCHEMA.parents")),
        )
        transaction(database) {
            exec("CREATE TABLE $RENAME_SCHEMA.parents (id INTEGER PRIMARY KEY)")
            exec("CREATE TABLE $RENAME_SCHEMA.children (parent INTEGER NOT NULL REFERENCES $RENAME_SCHEMA.parents(id))")
            exec("CREATE INDEX children_parent_idx ON $RENAME_SCHEMA.children(parent)")
            exec("CREATE VIEW $RENAME_SCHEMA.parent_view AS SELECT id FROM $RENAME_SCHEMA.parents")
            exec("INSERT INTO $RENAME_SCHEMA.parents VALUES (1)")
            exec("INSERT INTO $RENAME_SCHEMA.children VALUES (1)")
        }
        val first = PostgresMigrationGenerator.plan(database, desired, nonDestructive = true)
        assertTrue(first.statements.first().contains("RENAME COLUMN"))
        assertTrue(first.suppressedStatements.isEmpty(), first.toString())
        apply(first)
        assertTrue(PostgresMigrationGenerator.plan(database, desired, true).isEmpty)
        transaction(database) {
            assertEquals(1, exec("SELECT id FROM $RENAME_SCHEMA.parent_view") { result ->
                check(result.next())
                result.getInt(1)
            })
            exec("INSERT INTO $RENAME_SCHEMA.parents VALUES (2)")
            exec("INSERT INTO $RENAME_SCHEMA.children VALUES (2)")
        }
    }

    @Test
    fun `ambiguity fails before writing output or changing the database`() {
        transaction(database) { exec("CREATE TABLE $RENAME_SCHEMA.users (name TEXT, full_name TEXT)") }
        val output = outputDirectory.resolve("ambiguous.sql")
        assertFailsWith<IllegalArgumentException> {
            PostgresMigrationGenerator.generate(database, schema, output, nonDestructive = true)
        }
        assertFalse(Files.exists(output))
        transaction(database) { exec("SELECT name, full_name FROM $RENAME_SCHEMA.users") { } }
    }

    @Test
    fun `generated SQL contains the complete plan and preserves the existing transaction`() {
        val output = outputDirectory.resolve("rename.sql")
        transaction(database) {
            exec("CREATE TABLE $RENAME_SCHEMA.users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
            exec("INSERT INTO $RENAME_SCHEMA.users VALUES (1, 'Grace')")
            val plan = PostgresMigrationGenerator.generate(schema, output, nonDestructive = true)
            assertEquals(plan.toSql(), Files.readString(output))
            // The caller's uncommitted data and original column are still available.
            assertEquals("Grace", exec("SELECT name FROM $RENAME_SCHEMA.users") { result ->
                check(result.next())
                result.getString(1)
            })
        }
    }

    @Test
    fun `renamed columns and changed views are compared together without rebuilding matching indexes`() {
        val indexed = object : Table("$RENAME_SCHEMA.users") {
            val id = integer("id")
            val displayName = text("display_name").renamedFrom("name")
            override val primaryKey = PrimaryKey(id)
            init { index("users_label_idx", false, displayName) }
        }
        val desired = schema(indexed, views = listOf(
            PostgresViewDefinition("labels", "SELECT display_name AS label FROM $RENAME_SCHEMA.users WHERE id > 0"),
        ))
        transaction(database) {
            exec("CREATE TABLE $RENAME_SCHEMA.users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
            exec("CREATE INDEX users_label_idx ON $RENAME_SCHEMA.users(name)")
            exec("CREATE VIEW $RENAME_SCHEMA.labels AS SELECT name AS label FROM $RENAME_SCHEMA.users")
            exec("INSERT INTO $RENAME_SCHEMA.users VALUES (1, 'Ada'), (-1, 'Hidden')")
        }
        val plan = PostgresMigrationGenerator.plan(database, desired, nonDestructive = true)
        assertTrue(plan.statements.first().contains("RENAME COLUMN"))
        assertTrue(plan.statements.any { it.startsWith("CREATE OR REPLACE VIEW") }, plan.toSql())
        assertFalse(plan.statements.any { it.contains("INDEX") }, plan.toSql())
        transaction(database) { exec("SELECT name FROM $RENAME_SCHEMA.users") { } }
        apply(plan)
        assertTrue(PostgresMigrationGenerator.plan(database, desired, true).isEmpty)
        transaction(database) {
            assertEquals(listOf("Ada"), exec("SELECT label FROM $RENAME_SCHEMA.labels") { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            })
        }
    }

    @Test
    fun `SQL planning failure rolls back renames preserves prior writes and leaves transaction usable`() {
        transaction(database) {
            exec("CREATE TABLE $RENAME_SCHEMA.users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
            exec("CREATE VIEW $RENAME_SCHEMA.labels AS SELECT name AS label FROM $RENAME_SCHEMA.users")
        }
        val invalid = schema(users, views = listOf(
            PostgresViewDefinition("labels", "SELECT missing_column FROM $RENAME_SCHEMA.users"),
        ))
        val output = outputDirectory.resolve("failed.sql")
        transaction(database) {
            exec("INSERT INTO $RENAME_SCHEMA.users VALUES (1, 'Before')")
            assertFailsWith<SQLException> {
                PostgresMigrationGenerator.generate(invalid, output, nonDestructive = true)
            }
            assertFalse(Files.exists(output))
            exec("INSERT INTO $RENAME_SCHEMA.users VALUES (2, 'After')")
            // Cache restoration also permits another plan in the same transaction after failure.
            val valid = schema(users, views = listOf(
                PostgresViewDefinition("labels", "SELECT display_name AS label FROM $RENAME_SCHEMA.users"),
            ))
            assertTrue(PostgresMigrationGenerator.plan(valid, true).statements.first().contains("RENAME COLUMN"))
        }
        transaction(database) {
            assertEquals(listOf("Before", "After"), exec("SELECT name FROM $RENAME_SCHEMA.users ORDER BY id") { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            })
        }
    }

    @Test
    fun `simulation releases table locks before the caller transaction finishes`() {
        transaction(database) {
            exec("CREATE TABLE $RENAME_SCHEMA.users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        }
        Executors.newSingleThreadExecutor().use { executor ->
            transaction(database) {
                exec("INSERT INTO $RENAME_SCHEMA.users VALUES (1, 'Before')")
                PostgresMigrationGenerator.plan(schema, nonDestructive = true)
                executor.submit {
                    transaction(database) {
                        maxAttempts = 1
                        // Compatible with the caller's INSERT, but not a leaked rename lock.
                        exec("LOCK TABLE $RENAME_SCHEMA.users IN ACCESS SHARE MODE NOWAIT")
                        exec("SELECT name FROM $RENAME_SCHEMA.users") { }
                    }
                }.get(5, TimeUnit.SECONDS)
                assertEquals(1, exec("SELECT count(*) FROM $RENAME_SCHEMA.users") { result ->
                    check(result.next())
                    result.getInt(1)
                })
            }
        }
    }

    private fun apply(plan: PostgresMigrationPlan) {
        transaction(database) { plan.statements.forEach { exec(it) } }
    }

    private fun schema(vararg tables: Table, views: List<PostgresViewDefinition> = emptyList()) =
        object : KeepSchema(RENAME_SCHEMA) {
            override val tables = tables.toList()
            override val views = views
        }
}
