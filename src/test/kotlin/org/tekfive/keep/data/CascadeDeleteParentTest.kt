package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.schema.PostgresRenderContext
import org.tekfive.keep.schema.PostgresRowTriggerDefinition
import org.tekfive.keep.schema.PostgresTriggerEvent
import org.tekfive.keep.schema.PostgresTriggerTiming
import org.tekfive.keep.schema.postgresObjects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CascadeDeclarationData(val parentId: Long?) : Data()

class CascadeDeleteParentTest {
    private fun table(name: String) = object : DataTable<CascadeDeclarationData>(name) {}
    private fun trigger(table: TypedDataTuple<*, *>) = table.columnPostgresObjects.single() as PostgresRowTriggerDefinition

    @Test
    fun `retains column property binding nullability and foreign key action`() {
        val parent = table("parents")
        val child = table("children")
        val column = child.fkey(CascadeDeclarationData::parentId, parent, ReferenceOption.NO_ACTION)
        assertSame(column, column.cascadeDeleteParent())
        assertEquals(CascadeDeclarationData::parentId, column.dataProperty)
        assertTrue(column.columnType.nullable)
        assertEquals(ReferenceOption.NO_ACTION, column.foreignKey!!.deleteRule)
        val trigger = trigger(child)
        assertEquals("children_parent_id_delete_parent", trigger.name)
        assertEquals(PostgresTriggerTiming.AFTER, trigger.timing)
        assertEquals(setOf(PostgresTriggerEvent.DELETE), trigger.events)
        val body = trigger.functionBody(PostgresRenderContext("app"))
        assertTrue(body.contains("IF OLD.\"parent_id\" IS NOT NULL THEN"), body)
        assertTrue(body.contains("DELETE FROM \"app\".\"parents\" WHERE \"id\" = OLD.\"parent_id\";"), body)
    }

    @Test
    fun `supports nullable chaining in either order`() {
        val parent = table("parents")
        for (nullableFirst in listOf(true, false)) {
            val child = table("children")
            with(child) {
                val required = fkey("parent_id", parent)
                if (nullableFirst) required.nullable().cascadeDeleteParent()
                else required.cascadeDeleteParent().nullable()
            }
            assertTrue(trigger(child).functionBody(PostgresRenderContext("app")).contains("OLD.\"parent_id\""))
        }
    }

    @Test
    fun `uses referenced column and quotes identifiers and explicit target schema`() {
        val parent = object : Table("\"other\".\"parent items\"") {
            val code = text("external\"code").uniqueIndex()
        }
        val child = table("children")
        child.reference("parent\"code", parent.code).cascadeDeleteParent("delete\"parent")
        val trigger = trigger(child)
        val body = trigger.functionBody(PostgresRenderContext("app"))
        assertTrue(body.contains("DELETE FROM \"other\".\"parent items\" WHERE \"external\"\"code\" = OLD.\"parent\"\"code\";"), body)
        assertTrue(trigger.createTriggerStatement(PostgresRenderContext("app")).contains("\"delete\"\"parent\""))
    }

    @Test
    fun `generated names are deterministic distinct and fit the UTF8 identifier limit`() {
        fun names(columnName: String): List<String> {
            val child = table("😀".repeat(15))
            val parent = table("parents")
            child.reference(columnName, parent.id).cascadeDeleteParent()
            return trigger(child).let { listOf(it.name, it.functionName) }
        }
        val first = names("parent_" + "x".repeat(50))
        assertEquals(first, names("parent_" + "x".repeat(50)))
        val second = names("parent_" + "x".repeat(49) + "y")
        assertTrue(first.zip(second).all { (a, b) -> a != b })
        (first + second).forEach { name ->
            assertTrue(name.toByteArray(Charsets.UTF_8).size <= 63)
            assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
    }

    @Test
    fun `rejects missing composite and ambiguous foreign keys and non KEEP tables`() {
        val child = table("children")
        assertFailsWith<IllegalArgumentException> { child.long("missing_id").cascadeDeleteParent() }
        val parents = object : Table("parents") {
            val first = long("first")
            val second = long("second")
            override val primaryKey = PrimaryKey(first, second)
        }
        val first = child.long("first")
        val second = child.long("second")
        child.foreignKey(first to parents.first, second to parents.second)
        assertFailsWith<IllegalArgumentException> { first.cascadeDeleteParent() }
        child.foreignKey(first to table("single_target").id)
        assertFailsWith<IllegalArgumentException> { first.cascadeDeleteParent() }
        assertTrue(child.columnPostgresObjects.isEmpty())

        val plain = object : Table("plain") {}
        assertFailsWith<IllegalArgumentException> { plain.reference("parent_id", table("single_parent").id).cascadeDeleteParent() }
    }

    @Test
    fun `rejects invalid custom names without registering a trigger`() {
        val child = table("children")
        val column = child.reference("parent_id", table("parents").id)
        for (name in listOf("", " ", "x".repeat(64), "invalid\u0000name")) {
            assertFailsWith<IllegalArgumentException> { column.cascadeDeleteParent(name) }
        }
        assertTrue(child.columnPostgresObjects.isEmpty())
    }

    @Test
    fun `typed delete operation is available in explicit triggers and validates ownership`() {
        val child = table("children")
        val column = child.reference("parent_id", table("parents").id)
        val other = table("other")
        assertFailsWith<IllegalArgumentException> {
            other.postgresObjects {
                rowTrigger("wrong_owner") {
                    after(PostgresTriggerEvent.DELETE)
                    onDelete { deleteReferencedRow(column) }
                }
            }
        }
        val trigger = child.postgresObjects {
            rowTrigger("delete_parent") {
                after(PostgresTriggerEvent.DELETE)
                onDelete { deleteReferencedRow(column) }
            }
        }.single() as PostgresRowTriggerDefinition
        assertTrue(trigger.functionBody(PostgresRenderContext("app")).contains("DELETE FROM"))
    }
}
