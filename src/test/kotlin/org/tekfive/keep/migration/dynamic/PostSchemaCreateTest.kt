package org.tekfive.keep.migration.dynamic

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.DataTableSchema
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.schema.AppSchema
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresForeignKeyAction
import org.tekfive.keep.schema.PostgresFreshInstallGenerator
import org.tekfive.keep.schema.postgresObjects
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val SCHEMA = "keep_post_schema_test"
private const val FK = "assignments_current_stage_version_fk"

private abstract class HookTable(name: String) : Table("$SCHEMA.$name"), DataTableSchema {
}

private class Stages : HookTable("stages") {
    val id = integer("id")
    val version = integer("pipeline_version_id")
    override val primaryKey = PrimaryKey(id)
    override val postgresObjects = postgresObjects { uniqueConstraint("stages_version_id_uq", version, id) }
}

private class Assignments(val stages: Stages) : HookTable("assignments") {
    val version = integer("pipeline_version_id")
    val stage = integer("current_stage_id")
    val ordinaryReference = integer("ordinary_reference").references(stages.id).nullable()
    var includeForeignKey = true
    var deleteAction = PostgresForeignKeyAction.NO_ACTION
    var deferred = false
    var reverseOrder = false
    override val postgresObjects get() = postgresObjects {
        if (includeForeignKey) {
            val pairs = listOf(version to stages.version, stage to stages.id).let { if (reverseOrder) it.reversed() else it }
            foreignKeyConstraint(FK, *pairs.toTypedArray(), onDelete = deleteAction,
                onUpdate = PostgresForeignKeyAction.CASCADE, deferrable = deferred, initiallyDeferred = deferred)
        }
    }
}

class PostSchemaCreateTest {
    private lateinit var database: Database
    private val stages = Stages()
    private val assignments = Assignments(stages)
    private val schema = object : AppSchema(SCHEMA) {
        override val tables = listOf(assignments, stages)
        override val sequences = emptyList<String>()
    }

    @BeforeTest fun setup() {
        database = TestDatabase.connect()
        transaction(database) { exec("CREATE SCHEMA $SCHEMA") }
    }
    @AfterTest fun cleanup() {
        transaction(database) { exec("DROP SCHEMA $SCHEMA CASCADE") }
    }

    @Test fun `dynamic plan installs cross-table composite foreign key after tables and unique keys`() {
        val plan = PostgresMigrationGenerator.plan(database, schema, true)
        val foreignKeyIndex = plan.statements.indexOfFirst { it is AddConstraint && it.definition.name == FK }
        val uniqueIndex = plan.statements.indexOfFirst { it is AddConstraint && it.definition is ConstraintDefinition.Unique }
        assertTrue(foreignKeyIndex > uniqueIndex)
        assertTrue(uniqueIndex > plan.statements.indexOfLast { it is CreateTable })
        plan.execute(database)
        verifyInstalled()
    }

    @Test fun `fresh install and AppSchema create both apply typed post-schema objects`() {
        transaction(database) { PostgresFreshInstallGenerator.plan(schema).statements.forEach { exec(it) } }
        verifyInstalled()
        transaction(database) {
            exec("DROP SCHEMA $SCHEMA CASCADE")
            exec("CREATE SCHEMA $SCHEMA")
            schema.create()
        }
        verifyInstalled()
    }

    @Test fun `foreign key changes and removal are compared while Exposed foreign keys are retained`() {
        PostgresMigrationGenerator.plan(database, schema, true).execute(database)
        assignments.deleteAction = PostgresForeignKeyAction.CASCADE
        assignments.deferred = true
        assignments.reverseOrder = true
        val changed = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(2, changed.statements.size)
        assertEquals(FK, assertIs<DropConstraint>(changed.statements[0]).name)
        val definition = assertIs<ConstraintDefinition.ForeignKey>(assertIs<AddConstraint>(changed.statements[1]).definition)
        assertEquals(listOf("current_stage_id", "pipeline_version_id"), definition.columns)
        assertTrue(definition.initiallyDeferred)
        changed.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        assignments.includeForeignKey = false
        val removed = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(FK, assertIs<DropConstraint>(removed.statements.single()).name)
        removed.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        transaction(database) {
            assertEquals(1, exec("SELECT count(*) FROM pg_constraint WHERE conrelid = '$SCHEMA.assignments'::regclass AND contype = 'f'") { it.next(); it.getInt(1) })
        }
    }

    @Test fun `matching not-valid foreign keys are validated without replacing them`() {
        PostgresMigrationGenerator.plan(database, schema, true).execute(database)
        transaction(database) {
            exec("ALTER TABLE $SCHEMA.assignments DROP CONSTRAINT $FK")
            exec("ALTER TABLE $SCHEMA.assignments ADD CONSTRAINT $FK FOREIGN KEY (pipeline_version_id, current_stage_id) REFERENCES $SCHEMA.stages(pipeline_version_id, id) ON UPDATE CASCADE NOT VALID")
        }
        val plan = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(FK, assertIs<ValidateConstraint>(plan.statements.single()).name)
        plan.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
    }

    @Test fun `Exposed foreign keys with database-assigned names are preserved`() {
        PostgresMigrationGenerator.plan(database, schema, true).execute(database)
        transaction(database) {
            exec("ALTER TABLE $SCHEMA.assignments RENAME CONSTRAINT ${assignments.foreignKeys.single().fkName} TO original_reference_fk")
        }
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
    }

    @Test fun `foreign key names cannot overwrite a different kind of constraint`() {
        assignments.includeForeignKey = false
        PostgresMigrationGenerator.plan(database, schema, true).execute(database)
        transaction(database) { exec("ALTER TABLE $SCHEMA.assignments ADD CONSTRAINT $FK CHECK (pipeline_version_id > 0)") }
        assignments.includeForeignKey = true
        assertFailsWith<IllegalArgumentException> { PostgresMigrationGenerator.plan(database, schema, true) }
    }

    @Test fun `undeclared targets and invalid column pairs are rejected`() {
        val incomplete = object : KeepSchema(SCHEMA) { override val tables = listOf(assignments) }
        assertFailsWith<IllegalArgumentException> { PostgresMigrationGenerator.plan(database, incomplete, true) }
        assertFailsWith<IllegalArgumentException> { PostgresFreshInstallGenerator.plan(incomplete) }
        assertFailsWith<IllegalArgumentException> {
            assignments.postgresObjects { foreignKeyConstraint("invalid", stages.id to assignments.stage) }
        }
        assertFailsWith<IllegalArgumentException> {
            assignments.postgresObjects { foreignKeyConstraint("invalid", assignments.stage to stages.id, assignments.version to assignments.version) }
        }
    }

    private fun verifyInstalled() {
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, false).isEmpty)
        transaction(database) {
            exec("INSERT INTO $SCHEMA.stages (id, pipeline_version_id) VALUES (10, 1), (20, 2)")
            exec("INSERT INTO $SCHEMA.assignments (pipeline_version_id, current_stage_id) VALUES (1, 10)")
        }
        assertFailsWith<SQLException> {
            transaction(database) {
                maxAttempts = 1
                exec("INSERT INTO $SCHEMA.assignments (pipeline_version_id, current_stage_id) VALUES (1, 20)")
            }
        }
    }
}
