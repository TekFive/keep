package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.jfk.toJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Concrete subclass for testing. */
private object TestChangeTable : DataChangeTable("test_changes")

class DataChangeTableTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction {
            SchemaUtils.create(TestChangeTable)
        }
    }

    @AfterTest
    fun teardown() {
        transaction {
            SchemaUtils.drop(TestChangeTable)
        }
    }

    @Test
    fun `table has id, createdAt, and changes columns`() {
        val columnNames = TestChangeTable.columns.map { it.name }
        assertTrue("id" in columnNames)
        assertTrue("created_at" in columnNames)
        assertTrue("changes" in columnNames)
    }

    @Test
    fun `primary key is id`() {
        val pk = TestChangeTable.primaryKey
        assertNotNull(pk)
        assertEquals(1, pk.columns.size)
        assertTrue(TestChangeTable.id in pk.columns)
    }

    @Test
    fun `insert and read back a change record`() {
        transaction {
            val json = mapOf("score" to 100, "active" to false).toJsonObject()
            val now = System.currentTimeMillis()

            TestChangeTable.insert {
                it[createdAt] = now
                it[changes] = json
            }

            val row = TestChangeTable.selectAll().single()
            assertEquals(now, row[TestChangeTable.createdAt])

            val loaded = row[TestChangeTable.changes]
            assertEquals(100, loaded["score"].int)
            assertEquals(false, loaded["active"].boolean)
        }
    }

    @Test
    fun `stores dirty properties from Data object`() {
        transaction {
            SchemaUtils.create(SimpleTable)

            val data = SimpleData("alice", 42)
            SimpleTable.create(data)
            data.score = 100

            val dirtyJson = data.dirtyPropertiesAsJson()
            val now = System.currentTimeMillis()

            TestChangeTable.insert {
                it[createdAt] = now
                it[changes] = dirtyJson
            }

            val row = TestChangeTable.selectAll().single()
            val loaded = row[TestChangeTable.changes]
            assertEquals(100, loaded["score"].int)

            SchemaUtils.drop(SimpleTable)
        }
    }

    @Test
    fun `multiple change records preserve order`() {
        transaction {
            val t1 = 1000L
            val t2 = 2000L

            TestChangeTable.insert {
                it[createdAt] = t1
                it[changes] = mapOf("score" to 42).toJsonObject()
            }
            TestChangeTable.insert {
                it[createdAt] = t2
                it[changes] = mapOf("score" to 100).toJsonObject()
            }

            val rows = TestChangeTable.selectAll()
                .orderBy(TestChangeTable.createdAt)
                .toList()

            assertEquals(2, rows.size)
            assertEquals(42, rows[0][TestChangeTable.changes]["score"].int)
            assertEquals(100, rows[1][TestChangeTable.changes]["score"].int)
        }
    }
}
