package org.tekfive.keep.migration.dynamic

import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.DataTableSchema
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresEnumDefinition
import org.tekfive.keep.schema.PostgresFreshInstallGenerator
import org.tekfive.keep.schema.PostgresIndexKey
import org.tekfive.keep.schema.indexKey
import org.tekfive.keep.schema.postgresObjects
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val SCHEMA = "keep_typed_declarations_test"

class TypedSchemaDeclarationsTest {
    private lateinit var database: Database
    private var labels = listOf("new", "done")
    private var withIndex = true
    private var predicate = "score >= 0"
    private var unique = false
    private var withConstraints = false
    private val records = object : Table("$SCHEMA.records"), DataTableSchema {
        val id = integer("id")
        val score = integer("score")
        val label = text("label")
        val status = registerColumn<String>("status", object : ColumnType<String>() {
            override fun sqlType() = "\"$SCHEMA\".\"status\""
            override fun valueFromDB(value: Any) = value.toString()
        })
        override val primaryKey = PrimaryKey(id)
        override val postgresObjects get() = postgresObjects {
            if (withConstraints) {
                checkConstraint("records_score_check", SqlExpression(predicate))
                exclusionConstraint("records_score_exclusion", listOf(ExclusionElement(SqlExpression("int4range(score, score + 1)"), "&&")))
            }
            if (withIndex) index("records_search_idx", listOf(score.indexKey(SortOrder.DESC),
                PostgresIndexKey.ExpressionKey(SqlExpression("lower(label)"))),
                unique = unique, include = listOf(id), predicate = SqlExpression(predicate))
        }
    }
    private val schema = object : KeepSchema(SCHEMA) {
        override val tables = listOf(records)
        override val types get() = listOf(PostgresEnumDefinition("status", labels))
    }

    @BeforeTest fun setup() { database = TestDatabase.connect() }
    @AfterTest fun cleanup() { transaction(database) { exec("DROP SCHEMA IF EXISTS $SCHEMA CASCADE") } }

    @Test fun `new schema types and indexes are typed ordered executable and idempotent`() {
        val plan = PostgresMigrationGenerator.plan(database, schema, true)
        assertIs<CreateSchema>(plan.statements[0])
        assertIs<CreateEnumType>(plan.statements[1])
        assertTrue(plan.statements.indexOfFirst { it is CreateIndex } > plan.statements.indexOfFirst { it is CreateTable })
        transaction(database) {
            assertEquals(null, exec("SELECT to_regtype('$SCHEMA.status')") { it.next(); it.getString(1) })
        }
        plan.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        predicate = "((score >= 0))"
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        transaction(database) { exec("INSERT INTO $SCHEMA.records VALUES (1, 1, 'Hello', 'new')") }
    }

    @Test fun `fresh install indexes are recognized then replaced or removed without losing data`() {
        transaction(database) { PostgresFreshInstallGenerator.plan(schema).statements.forEach { exec(it) } }
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        transaction(database) { exec("INSERT INTO $SCHEMA.records VALUES (1, 1, 'Hello', 'new')") }
        predicate = "score > 0"
        unique = true
        val changed = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(listOf(DropIndex::class, CreateIndex::class), changed.statements.map { it::class })
        changed.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, false).isEmpty)
        withIndex = false
        val removed = PostgresMigrationGenerator.plan(database, schema, true)
        assertIs<DropIndex>(removed.statements.single())
        removed.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        transaction(database) { assertEquals(1, exec("SELECT count(*) FROM $SCHEMA.records") { it.next(); it.getInt(1) }) }
    }

    @Test fun `enum additions preserve order require autocommit and do not mutate planning state`() {
        PostgresMigrationGenerator.plan(database, schema, true).execute(database)
        labels = listOf("new", "queued", "running", "done", "archived")
        val plan = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(listOf("queued", "running", "archived"), plan.statements.filterIsInstance<AddEnumValue>().map { it.value })
        assertFailsWith<IllegalArgumentException> { plan.execute(database) }
        assertEquals(3, plan.executeAutocommit(database))
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        transaction(database) {
            assertEquals(labels, exec("SELECT unnest(enum_range(NULL::$SCHEMA.status))::text") { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            })
            exec("INSERT INTO $SCHEMA.records VALUES (1, 1, 'New', 'running')")
        }
        labels = listOf("done", "new", "queued", "running", "archived")
        assertFailsWith<IllegalArgumentException> { PostgresMigrationGenerator.plan(database, schema, false) }
        labels = listOf("new", "done")
        assertFailsWith<IllegalArgumentException> { PostgresMigrationGenerator.plan(database, schema, false) }
    }

    @Test fun `typed check and exclusion constraints are compared replaced and removed`() {
        withConstraints = true
        PostgresMigrationGenerator.plan(database, schema, true).execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        predicate = "score > 0"
        val changed = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(1, changed.statements.filterIsInstance<DropConstraint>().size)
        changed.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        withConstraints = false
        val removed = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(2, removed.statements.filterIsInstance<DropConstraint>().size)
        removed.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
    }

    @Test fun `removed enums are suppressed in non-destructive mode and dropped after dependent tables`() {
        PostgresMigrationGenerator.plan(database, schema, true).execute(database)
        val empty = object : KeepSchema(SCHEMA) { override val tables = emptyList<Table>() }
        val safe = PostgresMigrationGenerator.plan(database, empty, true)
        assertTrue(safe.statements.isEmpty())
        assertTrue(safe.suppressedStatements.any { it.statement is DropType })
        val destructive = PostgresMigrationGenerator.plan(database, empty, false)
        assertTrue(destructive.statements.indexOfFirst { it is DropType } > destructive.statements.indexOfFirst { it is DropTable })
        destructive.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, empty, false).isEmpty)
    }
}
