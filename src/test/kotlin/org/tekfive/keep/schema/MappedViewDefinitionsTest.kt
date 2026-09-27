package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.unionAll
import org.tekfive.keep.data.Data
import org.tekfive.keep.data.DataView
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.data.UuidData
import org.tekfive.keep.data.UuidDataView
import org.tekfive.keep.migration.dynamic.CreateView
import org.tekfive.keep.migration.dynamic.DropView
import org.tekfive.keep.migration.dynamic.PostgresMigrationGenerator
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MAPPED_SCHEMA = "keep_mapped_views_test"
private object LiveRecords : Table("$MAPPED_SCHEMA.live_records") {
    val id = long("id")
    val label = text("label")
}
private object PastRecords : Table("$MAPPED_SCHEMA.past_records") {
    val id = long("id")
    val label = text("label")
}
private object UuidRecords : Table("$MAPPED_SCHEMA.uuid_records") {
    val id = javaUUID("id")
    val label = text("label")
}

class MappedViewRow(val label: String) : Data()
class MappedUuidViewRow(val label: String) : UuidData()

object MappedRecordsView : DataView<MappedViewRow>("$MAPPED_SCHEMA.records_view") {
    val label = text("label")
    override val viewDefinition = view(LiveRecords, PastRecords) {
        LiveRecords.select(LiveRecords.id, LiveRecords.label).unionAll(
            PastRecords.select(PastRecords.id, PastRecords.label),
        )
    }
}
object MappedDependentView : DataView<MappedViewRow>("$MAPPED_SCHEMA.dependent_view") {
    val label = text("label")
    override val viewDefinition = view(MappedRecordsView) {
        MappedRecordsView.select(MappedRecordsView.id, MappedRecordsView.label)
    }
}
object MappedUuidView : UuidDataView<MappedUuidViewRow>("$MAPPED_SCHEMA.uuid_view") {
    val label = text("label")
    override val viewDefinition = view(UuidRecords) { UuidRecords.select(UuidRecords.id, UuidRecords.label) }
}

class MappedViewDefinitionsTest {
    private lateinit var database: Database
    private val schema = object : AppSchema(MAPPED_SCHEMA) {
        override val tables = listOf(LiveRecords, PastRecords, UuidRecords)
        override val sequences = emptyList<String>()
        // Mixed mapped and standalone definitions, deliberately before their dependencies.
        override val views = listOf(
            MappedDependentView, MappedRecordsView, MappedUuidView,
            postgresView("labels", MappedRecordsView) { MappedRecordsView.select(MappedRecordsView.label) },
        )
    }

    @BeforeTest fun setup() { database = TestDatabase.connect() }
    @AfterTest fun cleanup() { transaction(database) { exec("DROP SCHEMA IF EXISTS $MAPPED_SCHEMA CASCADE") } }

    private fun assertMappedRows() {
        transaction(database) {
            exec("INSERT INTO $MAPPED_SCHEMA.live_records VALUES (1, 'live')")
            exec("INSERT INTO $MAPPED_SCHEMA.past_records VALUES (2, 'past')")
            exec("INSERT INTO $MAPPED_SCHEMA.uuid_records VALUES ('${UUID.randomUUID()}', 'uuid')")
            assertEquals(setOf("live", "past"), MappedDependentView.selectAll().map { MappedDependentView.map(it).label }.toSet())
            assertEquals("uuid", MappedUuidView.map(MappedUuidView.selectAll().single()).label)
        }
    }

    @Test fun `fresh installation resolves definitions owned by long and UUID views`() {
        val plan = PostgresFreshInstallGenerator.plan(schema)
        val views = plan.statements.filter { it.startsWith("CREATE VIEW") }
        assertTrue(views[0].contains("records_view"))
        assertTrue(views[1].contains("dependent_view"))
        transaction(database) { plan.statements.forEach { exec(it) } }
        assertMappedRows()
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
    }

    @Test fun `dynamic installation and structural replacement use the view-owned definition`() {
        val install = PostgresMigrationGenerator.plan(database, schema, true)
        transaction(database) { assertNull(exec("SELECT to_regclass('$MAPPED_SCHEMA.records_view')") { it.next(); it.getString(1) }) }
        install.execute(database)
        assertMappedRows()
        transaction(database) {
            exec("DROP VIEW $MAPPED_SCHEMA.uuid_view")
            exec("CREATE VIEW $MAPPED_SCHEMA.uuid_view AS SELECT id FROM $MAPPED_SCHEMA.uuid_records")
        }
        val replacement = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(listOf(DropView::class, CreateView::class), replacement.statements.map { it::class })
        replacement.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        transaction(database) { assertEquals("uuid", MappedUuidView.map(MappedUuidView.selectAll().single()).label) }
    }

    @Test fun `AppSchema lifecycle resolves view-owned definitions`() {
        transaction(database) {
            exec("CREATE SCHEMA $MAPPED_SCHEMA")
            schema.create()
        }
        assertMappedRows()
        transaction(database) {
            schema.drop()
            assertNull(exec("SELECT to_regclass('$MAPPED_SCHEMA.records_view')") { it.next(); it.getString(1) })
        }
    }

    @Test fun `mapped definitions must target their own name and schema`() {
        val wrongName = object : DataView<MappedViewRow>("$MAPPED_SCHEMA.expected") {
            override val viewDefinition = PostgresViewDefinition("different", "SELECT 1::bigint AS id")
        }
        val wrongSchema = object : DataView<MappedViewRow>("elsewhere.wrong_schema") {
            override val viewDefinition = PostgresViewDefinition("wrong_schema", "SELECT 1::bigint AS id")
        }
        for (mapped in listOf(wrongName, wrongSchema)) {
            val invalid = object : KeepSchema(MAPPED_SCHEMA) {
                override val tables = emptyList<Table>()
                override val views = listOf(mapped)
            }
            assertFailsWith<IllegalArgumentException> { PostgresFreshInstallGenerator.plan(invalid) }
        }
    }
}
