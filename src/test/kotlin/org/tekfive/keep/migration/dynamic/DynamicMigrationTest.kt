package org.tekfive.keep.migration.dynamic

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.io.TempDir
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.data.renamedFrom
import org.tekfive.keep.schema.KeepSchema
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val SCHEMA = "keep_dynamic_test"

class DynamicMigrationTest {
    private lateinit var database: Database
    private val table = QualifiedName("records", SCHEMA)
    @TempDir lateinit var output: Path

    @BeforeTest fun setup() {
        database = TestDatabase.connect()
        transaction(database) { exec("CREATE SCHEMA $SCHEMA") }
    }
    @AfterTest fun cleanup() {
        transaction(database) { exec("DROP SCHEMA $SCHEMA CASCADE") }
    }

    @Test fun `generator returns typed changes and executor applies the complete rename plan`() {
        val records = object : Table("$SCHEMA.records") {
            val id = integer("id")
            val value = long("value").renamedFrom("old_value")
            override val primaryKey = PrimaryKey(id)
        }
        val schema = object : KeepSchema(SCHEMA) { override val tables = listOf(records) }
        transaction(database) {
            exec("CREATE TABLE $SCHEMA.records (id INTEGER PRIMARY KEY, old_value INTEGER NOT NULL)")
            exec("INSERT INTO $SCHEMA.records VALUES (1, 123)")
        }
        val safe = PostgresMigrationGenerator.plan(database, schema, true)
        assertIs<RenameColumn>(safe.statements.first())
        assertIs<AlterColumnType>(safe.suppressedStatements.single().statement)
        val plan = PostgresMigrationGenerator.plan(database, schema, false)
        assertEquals(listOf(RenameColumn::class, AlterColumnType::class), plan.statements.map { it::class })
        transaction(database) { exec("SELECT old_value FROM $SCHEMA.records") { } }
        assertEquals(2, plan.execute(database))
        assertTrue(PostgresMigrationGenerator.plan(database, schema, false).isEmpty)
        transaction(database) { assertEquals(123L, exec("SELECT value FROM $SCHEMA.records") { it.next(); it.getLong(1) }) }
    }

    @Test fun `non-destructive removal retains column data and allows inserts to omit removed columns`() {
        val records = object : Table("$SCHEMA.records") {
            val id = integer("id")
            val required = text("required")
            override val primaryKey = PrimaryKey(id)
        }
        val schema = object : KeepSchema(SCHEMA) { override val tables = listOf(records) }
        transaction(database) {
            exec("""CREATE TABLE $SCHEMA.records (id INTEGER PRIMARY KEY, required TEXT NOT NULL, "old label" TEXT NOT NULL, optional TEXT)""")
            exec("""INSERT INTO $SCHEMA.records VALUES (1, 'required', 'preserved', 'optional')""")
        }

        val destructive = PostgresMigrationGenerator.plan(database, schema, false)
        assertEquals(2, destructive.statements.filterIsInstance<DropColumn>().size)
        assertTrue(destructive.statements.none { it is DropNotNull })

        val plan = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(listOf(DropNotNull(table, "old label")), plan.statements)
        assertEquals(setOf("old label", "optional"), plan.suppressedStatements.map {
            assertIs<DropColumn>(it.statement).column
        }.toSet())
        assertEquals(1, plan.execute(database))
        transaction(database) {
            exec("INSERT INTO $SCHEMA.records (id, required) VALUES (2, 'new')")
            assertEquals("preserved", exec("""SELECT "old label" FROM $SCHEMA.records WHERE id = 1""") { it.next(); it.getString(1) })
            assertTrue(exec("""SELECT "old label" IS NULL FROM $SCHEMA.records WHERE id = 2""") { it.next(); it.getBoolean(1) } == true)
            assertEquals(2, exec("SELECT count(*) FROM information_schema.columns WHERE table_schema = '$SCHEMA' AND table_name = 'records' AND is_nullable = 'NO'") { it.next(); it.getInt(1) })
        }
        val repeated = PostgresMigrationGenerator.plan(database, schema, true)
        assertTrue(repeated.statements.isEmpty())
        assertEquals(plan.suppressedStatements, repeated.suppressedStatements)
        assertEquals(2, PostgresMigrationGenerator.plan(database, schema, false).execute(database))
        assertTrue(PostgresMigrationGenerator.plan(database, schema, false).isEmpty)
    }

    @Test fun `manual plan renders and executes columns constraints expressions views and trigger functions`() {
        val function = QualifiedName("adjust_value", SCHEMA)
        val plan = PostgresMigrationPlan(listOf(
            CreateTable(table, listOf(
                ColumnDefinition("id", PostgresType("integer"), nullable = false, primaryKey = true),
                ColumnDefinition("value", PostgresType("text"), default = SqlExpression("'a,b'")),
            ), listOf(ConstraintDefinition.Check("positive_id", SqlExpression("id > 0")))),
            CreateIndex(IndexDefinition(QualifiedName("value_idx", SCHEMA), table, listOf(SqlExpression("lower(value)")), predicate = SqlExpression("value IS NOT NULL"))),
            CreateView(QualifiedName("values_view", SCHEMA), SqlQuery("SELECT value FROM ${table.toSql()}")),
            CreateOrReplaceFunction(FunctionDefinition(function, returns = PostgresType("trigger"), body = SqlBody("BEGIN NEW.value := upper(NEW.value); RETURN NEW; END;"))),
            CreateTrigger(TriggerDefinition("uppercase_value", table, function, TriggerTiming.BEFORE, setOf(TriggerEvent.INSERT))),
        ))
        val path = plan.writeTo(output.resolve("plan.sql"))
        assertEquals(plan.toSql(), Files.readString(path))
        assertEquals(5, plan.execute(database))
        transaction(database) {
            exec("INSERT INTO $SCHEMA.records (id) VALUES (1)")
            assertEquals("A,B", exec("SELECT value FROM $SCHEMA.values_view") { it.next(); it.getString(1) })
        }
    }

    @Test fun `atomic failure removes completed plan work and preserves caller work`() {
        transaction(database) { exec("CREATE TABLE $SCHEMA.audit (id INTEGER)") }
        val plan = PostgresMigrationPlan(listOf(
            CreateTable(table, listOf(ColumnDefinition("id", PostgresType("integer")))),
            AddColumn(table, ColumnDefinition("id", PostgresType("text"))),
        ))
        transaction(database) {
            exec("INSERT INTO $SCHEMA.audit VALUES (1)")
            val failure = assertFailsWith<PostgresMigrationExecutionException> { plan.execute() }
            assertEquals(1, failure.statementIndex)
            assertFalse(exists("records"))
            exec("INSERT INTO $SCHEMA.audit VALUES (2)")
        }
        transaction(database) { assertEquals(2, exec("SELECT count(*) FROM $SCHEMA.audit") { it.next(); it.getInt(1) }) }
        assertFailsWith<PostgresMigrationExecutionException> { plan.execute(database) }
        transaction(database) { assertFalse(exists("records")) }
    }

    @Test fun `preflight rejects autocommit operations before executing earlier statements`() {
        val plan = indexedPlan()
        assertFailsWith<IllegalArgumentException> { plan.execute(database) }
        transaction(database) { assertFalse(exists("records")) }
        assertEquals(2, plan.executeAutocommit(database))
        transaction(database) {
            assertTrue(exists("records"))
            assertTrue(exec("SELECT indisvalid FROM pg_index WHERE indexrelid = '$SCHEMA.records_idx'::regclass") { it.next(); it.getBoolean(1) } == true)
        }
        PostgresMigrationPlan(listOf(DropIndex(QualifiedName("records_idx", SCHEMA), concurrently = true))).executeAutocommit(database)
    }

    @Test fun `autocommit failure reports completed operations and is forbidden in caller transactions`() {
        transaction(database) {
            assertFailsWith<IllegalStateException> { indexedPlan().executeAutocommit(database) }
        }
        val plan = PostgresMigrationPlan(listOf(
            CreateTable(table, listOf(ColumnDefinition("id", PostgresType("integer")))),
            AddColumn(QualifiedName("missing", SCHEMA), ColumnDefinition("value", PostgresType("text"))),
        ))
        val failure = assertFailsWith<PostgresMigrationExecutionException> { plan.executeAutocommit(database) }
        assertEquals(1, failure.statementIndex)
        transaction(database) { assertTrue(exists("records")) }
    }

    @Test fun `suppressed operations are never rendered or executed`() {
        val plan = PostgresMigrationPlan(
            listOf(CreateTable(table, listOf(ColumnDefinition("id", PostgresType("integer"))))),
            listOf(SuppressedPostgresMigrationStatement(DropSchema(SCHEMA, behavior = DropBehavior.CASCADE), DestructivePostgresMigrationChange.DROP_SCHEMA)),
        )
        assertFalse(plan.toSql().contains("DROP SCHEMA"))
        assertEquals(1, plan.execute(database))
        transaction(database) { assertTrue(exists("records")) }
    }

    @Test fun `enum additions have a commit boundary before a following default uses them`() {
        val enum = QualifiedName("status", SCHEMA)
        PostgresMigrationPlan(listOf(CreateEnumType(enum, listOf("new")))).execute(database)
        val plan = PostgresMigrationPlan(listOf(
            AddEnumValue(enum, "ready"),
            CreateTable(table, listOf(ColumnDefinition("status", PostgresType(enum.toSql()), default = SqlExpression("'ready'")))),
        ))
        assertFailsWith<IllegalArgumentException> { plan.execute(database) }
        assertEquals(2, plan.executeAutocommit(database))
        transaction(database) { exec("INSERT INTO $SCHEMA.records DEFAULT VALUES") }
    }

    private fun indexedPlan() = PostgresMigrationPlan(listOf(
        CreateTable(table, listOf(ColumnDefinition("id", PostgresType("integer")))),
        CreateIndex(IndexDefinition(QualifiedName("records_idx", SCHEMA), table, listOf(SqlExpression("id"))), concurrently = true),
    ))
    private fun exists(name: String): Boolean = org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.current()
        .exec("SELECT to_regclass('$SCHEMA.$name') IS NOT NULL") { it.next(); it.getBoolean(1) } == true
}
