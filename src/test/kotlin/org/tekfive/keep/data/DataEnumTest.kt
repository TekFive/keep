package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataEnumTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction {
            SchemaUtils.create(TaskTable, TaggedTable, TaggedSetTable)
        }
    }

    @AfterTest
    fun teardown() {
        transaction {
            SchemaUtils.drop(TaggedSetTable, TaggedTable, TaskTable)
        }
    }

    @Test
    fun `create persists enum by id`() {
        transaction {
            val task = TaskData("fix bug", Priority.HIGH)
            TaskTable.create(task)

            val row = TaskTable.selectAll().where { TaskTable.id eq task.id }.single()
            assertEquals(30, row[TaskTable.priority].id)
            assertEquals(Priority.HIGH, row[TaskTable.priority])
        }
    }

    @Test
    fun `map reads enum from id`() {
        transaction {
            val task = TaskData("write tests", Priority.LOW)
            TaskTable.create(task)

            val row = TaskTable.selectAll().where { TaskTable.id eq task.id }.single()
            val loaded = TaskTable.map(row)

            assertEquals("write tests", loaded.title)
            assertEquals(Priority.LOW, loaded.priority)
        }
    }

    @Test
    fun `update changes enum value`() {
        transaction {
            val task = TaskData("deploy", Priority.MEDIUM)
            TaskTable.create(task)

            task.priority = Priority.HIGH
            TaskTable.update(task, TaskTable.priority)

            val row = TaskTable.selectAll().where { TaskTable.id eq task.id }.single()
            assertEquals(Priority.HIGH, row[TaskTable.priority])
        }
    }

    @Test
    fun `round-trip preserves all enum values`() {
        transaction {
            for (p in Priority.entries) {
                val task = TaskData("task-${p.name}", p)
                TaskTable.create(task)

                val row = TaskTable.selectAll().where { TaskTable.id eq task.id }.single()
                val loaded = TaskTable.map(row)
                assertEquals(p, loaded.priority)
            }
        }
    }

    // -- companion map / mapOptional ------------------------------------------

    @Test
    fun `companion map throws for unknown id`() {
        assertFailsWith<IllegalArgumentException> {
            Priority.map(999)
        }
    }

    @Test
    fun `companion mapOptional returns null for null id`() {
        assertNull(Priority.mapOptional(null))
    }

    @Test
    fun `companion mapOptional returns null for unknown id`() {
        assertNull(Priority.mapOptional(999))
    }

    // -- enumList -------------------------------------------------------------

    @Test
    fun `enumList stores and retrieves list of enums`() {
        transaction {
            val data = TaggedData("test", listOf(Priority.LOW, Priority.HIGH))
            TaggedTable.create(data)

            val row = TaggedTable.selectAll().where { TaggedTable.id eq data.id }.single()
            val loaded = TaggedTable.map(row)

            assertEquals(listOf(Priority.LOW, Priority.HIGH), loaded.priorities)
        }
    }

    @Test
    fun `enumList handles empty list`() {
        transaction {
            val data = TaggedData("empty", emptyList())
            TaggedTable.create(data)

            val row = TaggedTable.selectAll().where { TaggedTable.id eq data.id }.single()
            val loaded = TaggedTable.map(row)

            assertTrue(loaded.priorities.isEmpty())
        }
    }

    // -- enumSet --------------------------------------------------------------

    @Test
    fun `enumSet stores and retrieves set of enums`() {
        transaction {
            val data = TaggedSetData("test", linkedSetOf(Priority.LOW, Priority.HIGH))
            TaggedSetTable.create(data)

            val row = TaggedSetTable.selectAll().where { TaggedSetTable.id eq data.id }.single()
            val loaded = TaggedSetTable.map(row)

            assertEquals(setOf(Priority.LOW, Priority.HIGH), loaded.priorities)
        }
    }

    @Test
    fun `enumSet handles empty set`() {
        transaction {
            val data = TaggedSetData("empty", emptySet())
            TaggedSetTable.create(data)

            val row = TaggedSetTable.selectAll().where { TaggedSetTable.id eq data.id }.single()
            val loaded = TaggedSetTable.map(row)

            assertTrue(loaded.priorities.isEmpty())
        }
    }

    // -- enumOrder ------------------------------------------------------------

    @Test
    fun `enumOrder sorts by displayText ascending`() {
        transaction {
            // Insert in id order: LOW(10), MEDIUM(20), HIGH(30)
            TaskTable.create(TaskData("low task", Priority.LOW))
            TaskTable.create(TaskData("medium task", Priority.MEDIUM))
            TaskTable.create(TaskData("high task", Priority.HIGH))

            // displayText order ASC: High, Low, Medium
            val results = TaskTable.selectAll()
                .orderBy(TaskTable.priority.enumOrder() to SortOrder.ASC)
                .map { TaskTable.map(it) }

            assertEquals(listOf(Priority.HIGH, Priority.LOW, Priority.MEDIUM), results.map { it.priority })
        }
    }

    @Test
    fun `enumOrder sorts by displayText descending`() {
        transaction {
            TaskTable.create(TaskData("low task", Priority.LOW))
            TaskTable.create(TaskData("medium task", Priority.MEDIUM))
            TaskTable.create(TaskData("high task", Priority.HIGH))

            // displayText order DESC: Medium, Low, High
            val results = TaskTable.selectAll()
                .orderBy(TaskTable.priority.enumOrder() to SortOrder.DESC)
                .map { TaskTable.map(it) }

            assertEquals(listOf(Priority.MEDIUM, Priority.LOW, Priority.HIGH), results.map { it.priority })
        }
    }

    // -- displayText ----------------------------------------------------------

    // -- validation -----------------------------------------------------------

    @Test
    fun `duplicate ids rejected by DataEnumColumnType`() {
        assertFailsWith<IllegalStateException> {
            DataEnumColumnType(enumValues<UnvalidatedDuplicateEnum>())
        }
    }

    @Test
    fun `validate catches duplicate ids at class-loading time`() {
        assertFailsWith<ExceptionInInitializerError> {
            // Accessing any constant triggers class loading and the companion init
            ValidatedDuplicateEnum.A
        }
    }

    @Test
    fun `validate error message includes conflicting ids and names`() {
        val ex = assertFailsWith<IllegalStateException> {
            DataEnum.validate(enumValues<UnvalidatedDuplicateEnum>().toList())
        }
        assertTrue(ex.message!!.contains("1"))
        assertTrue(ex.message!!.contains("A"))
        assertTrue(ex.message!!.contains("B"))
    }
}

/** Enum with duplicate ids and no companion validate — caught by DataEnumColumnType. */
private enum class UnvalidatedDuplicateEnum(override val id: Int) : DataEnum {
    A(1), B(1)
}

/** Enum with duplicate ids and companion validate — caught at class-loading time. */
private enum class ValidatedDuplicateEnum(override val id: Int) : DataEnum {
    A(1), B(1);
    companion object { init { DataEnum.validate(entries) } }
}
