package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.migration.dynamic.CreateOrReplaceFunction
import org.tekfive.keep.migration.dynamic.CreateTrigger
import org.tekfive.keep.migration.dynamic.DropTrigger
import org.tekfive.keep.migration.dynamic.PostgresMigrationGenerator
import org.tekfive.keep.schema.AppSchema
import org.tekfive.keep.schema.PostgresFreshInstallGenerator
import org.tekfive.keep.schema.PostgresRenderContext
import org.tekfive.keep.schema.PostgresRowTriggerDefinition
import java.sql.SQLException
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val DELETE_PARENT_SCHEMA = "keep_delete_parent_test"

class DeleteParentData : Data()
class DeleteParentChildData(val parentId: Long?) : Data()
class DeleteParentUuidData : UuidData()
class DeleteParentUuidChildData(val parentId: UUID?) : UuidData()

object DeleteParents : DataTable<DeleteParentData>("$DELETE_PARENT_SCHEMA.parents", "$DELETE_PARENT_SCHEMA.record_ids")
object DeleteParentChildren : DataTable<DeleteParentChildData>("$DELETE_PARENT_SCHEMA.children", "$DELETE_PARENT_SCHEMA.record_ids") {
    val parentColumn = fkey(DeleteParentChildData::parentId, DeleteParents, ReferenceOption.NO_ACTION)
        .cascadeDeleteParent()
}
object DeleteParentCascadeChildren : DataTable<DeleteParentChildData>("$DELETE_PARENT_SCHEMA.cascade_children", "$DELETE_PARENT_SCHEMA.record_ids") {
    val parentColumn = fkey(DeleteParentChildData::parentId, DeleteParents).cascadeDeleteParent("delete_parent")
}
object DeleteUuidParents : UuidDataTable<DeleteParentUuidData>("$DELETE_PARENT_SCHEMA.uuid_parents")
object DeleteUuidParentChildren : UuidDataTable<DeleteParentUuidChildData>("$DELETE_PARENT_SCHEMA.uuid_children") {
    val parentColumn = fkey(DeleteParentUuidChildData::parentId, DeleteUuidParents, ReferenceOption.NO_ACTION)
        .cascadeDeleteParent()
}

private object DeleteParentSchema : AppSchema(DELETE_PARENT_SCHEMA) {
    // Deliberately declare children before their parents.
    override val tables = listOf<Table>(DeleteParentChildren, DeleteParentCascadeChildren, DeleteUuidParentChildren, DeleteParents, DeleteUuidParents)
    override val sequences = listOf("record_ids")
}

class CascadeDeleteParentIntegrationTest {
    private lateinit var database: Database

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
    }

    @AfterTest
    fun cleanup() {
        transaction(database) { exec("DROP SCHEMA IF EXISTS $DELETE_PARENT_SCHEMA CASCADE") }
    }

    private fun install() {
        val statements = PostgresFreshInstallGenerator.plan(DeleteParentSchema).statements
        transaction(database) { statements.forEach { exec(it) } }
    }

    @Test
    fun `fresh install deletes referenced Long parents on bulk SQL deletes and skips nulls`() {
        install()
        transaction(database) {
            val parents = List(3) { DeleteParents.create(DeleteParentData()) }
            parents.take(2).forEach { DeleteParentChildren.create(DeleteParentChildData(it.id)) }
            DeleteParentChildren.create(DeleteParentChildData(null))
            exec("DELETE FROM $DELETE_PARENT_SCHEMA.children")
            assertEquals(0, DeleteParentChildren.selectAll().count())
            assertEquals(parents.last().id, DeleteParents.selectAll().single()[DeleteParents.id])
        }
        PostgresMigrationGenerator.plan(database, DeleteParentSchema, true).also { assertTrue(it.isEmpty, it.sqlStatements.joinToString("\n")) }
    }

    @Test
    fun `AppSchema installs UUID parent deletion and preserves property mapping`() {
        transaction(database) {
            exec("CREATE SCHEMA $DELETE_PARENT_SCHEMA")
            exec("SET LOCAL search_path TO $DELETE_PARENT_SCHEMA, public")
            DeleteParentSchema.create()
            val parent = DeleteUuidParents.create(DeleteParentUuidData())
            val child = DeleteUuidParentChildren.create(DeleteParentUuidChildData(parent.id))
            assertEquals(parent.id, DeleteUuidParentChildren.getById(child.id).parentId)
            DeleteUuidParentChildren.delete(child)
            assertEquals(0, DeleteUuidParents.selectAll().count())
            val noParent = DeleteUuidParentChildren.create(DeleteParentUuidChildData(null))
            DeleteUuidParentChildren.delete(noParent)
            assertEquals(0, DeleteUuidParentChildren.selectAll().count())
        }
    }

    @Test
    fun `NO ACTION preserves shared parents and rolls back the source deletion`() {
        install()
        val ids = transaction(database) {
            val parent = DeleteParents.create(DeleteParentData())
            val child = DeleteParentChildren.create(DeleteParentChildData(parent.id))
            DeleteParentChildren.create(DeleteParentChildData(parent.id))
            parent.id to child.id
        }
        assertFailsWith<SQLException> {
            transaction(database) {
                exec("DELETE FROM $DELETE_PARENT_SCHEMA.children WHERE id = ${ids.second}")
            }
        }
        assertFailsWith<SQLException> {
            transaction(database) {
                exec("DELETE FROM $DELETE_PARENT_SCHEMA.parents WHERE id = ${ids.first}")
            }
        }
        transaction(database) {
            assertEquals(1, DeleteParents.selectAll().count())
            assertEquals(2, DeleteParentChildren.selectAll().count())
        }
    }

    @Test
    fun `default CASCADE supports deletion in both directions including shared children`() {
        install()
        transaction(database) {
            val first = DeleteParents.create(DeleteParentData())
            val second = DeleteParents.create(DeleteParentData())
            val child = DeleteParentCascadeChildren.create(DeleteParentChildData(first.id))
            DeleteParentCascadeChildren.create(DeleteParentChildData(first.id))
            DeleteParentCascadeChildren.create(DeleteParentChildData(second.id))
            exec("DELETE FROM $DELETE_PARENT_SCHEMA.cascade_children WHERE id = ${child.id}")
            assertEquals(second.id, DeleteParents.selectAll().single()[DeleteParents.id])
            assertEquals(1, DeleteParentCascadeChildren.selectAll().count())
            exec("DELETE FROM $DELETE_PARENT_SCHEMA.parents WHERE id = ${second.id}")
            assertEquals(0, DeleteParents.selectAll().count())
            assertEquals(0, DeleteParentCascadeChildren.selectAll().count())
        }
    }

    @Test
    fun `rolling back child deletion also restores the parent`() {
        install()
        transaction(database) {
            val parent = DeleteParents.create(DeleteParentData())
            DeleteParentChildren.create(DeleteParentChildData(parent.id))
        }
        transaction(database) {
            exec("DELETE FROM $DELETE_PARENT_SCHEMA.children")
            assertEquals(0, DeleteParents.selectAll().count())
            rollback()
        }
        transaction(database) {
            assertEquals(1, DeleteParents.selectAll().count())
            assertEquals(1, DeleteParentChildren.selectAll().count())
        }
    }

    @Test
    fun `dynamic migration installs typed triggers and repairs function and timing changes idempotently`() {
        val plan = PostgresMigrationGenerator.plan(database, DeleteParentSchema, true)
        assertEquals(3, plan.statements.filterIsInstance<CreateOrReplaceFunction>().size)
        assertEquals(3, plan.statements.filterIsInstance<CreateTrigger>().size)
        plan.execute(database)
        PostgresMigrationGenerator.plan(database, DeleteParentSchema, true).also { assertTrue(it.isEmpty, it.sqlStatements.joinToString("\n")) }

        val trigger = DeleteParentChildren.columnPostgresObjects.single() as PostgresRowTriggerDefinition
        val context = PostgresRenderContext(DELETE_PARENT_SCHEMA)
        transaction(database) {
            exec("CREATE OR REPLACE FUNCTION ${context.qualified(trigger.functionName)}() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN OLD; END;'")
            exec("DROP TRIGGER ${context.identifier(trigger.name)} ON ${context.tableName(trigger.table)}")
            exec(trigger.createTriggerStatement(context).replace(" AFTER DELETE ", " BEFORE DELETE "))
        }
        val repair = PostgresMigrationGenerator.plan(database, DeleteParentSchema, true)
        assertEquals(1, repair.statements.filterIsInstance<CreateOrReplaceFunction>().size)
        assertEquals(1, repair.statements.filterIsInstance<DropTrigger>().size)
        assertEquals(1, repair.statements.filterIsInstance<CreateTrigger>().size)
        repair.execute(database)
        PostgresMigrationGenerator.plan(database, DeleteParentSchema, true).also { assertTrue(it.isEmpty, it.sqlStatements.joinToString("\n")) }
        transaction(database) {
            val parent = DeleteParents.create(DeleteParentData())
            DeleteParentChildren.create(DeleteParentChildData(parent.id))
            exec("DELETE FROM $DELETE_PARENT_SCHEMA.children")
            assertEquals(0, DeleteParents.selectAll().count())
        }
    }

    @Test
    fun `dynamic migration adds the shortcut to existing tables without changing their rows`() {
        val install = PostgresFreshInstallGenerator.plan(DeleteParentSchema)
        transaction(database) {
            install.statements.filterNot { it.startsWith("CREATE OR REPLACE FUNCTION") || it.startsWith("CREATE TRIGGER") }
                .forEach { exec(it) }
            val parent = DeleteParents.create(DeleteParentData())
            DeleteParentChildren.create(DeleteParentChildData(parent.id))
        }
        val plan = PostgresMigrationGenerator.plan(database, DeleteParentSchema, true)
        assertTrue(plan.statements.all { it is CreateOrReplaceFunction || it is CreateTrigger }, plan.sqlStatements.joinToString("\n"))
        plan.execute(database)
        transaction(database) {
            assertEquals(1, DeleteParents.selectAll().count())
            assertEquals(1, DeleteParentChildren.selectAll().count())
            exec("DELETE FROM $DELETE_PARENT_SCHEMA.children")
            assertEquals(0, DeleteParents.selectAll().count())
        }
    }
}
