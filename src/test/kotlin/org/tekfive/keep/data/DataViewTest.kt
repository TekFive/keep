package org.tekfive.keep.data

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataViewTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction {
            SchemaUtils.create(SimpleTable)
            // Create a view backed by SimpleTable
            exec("CREATE OR REPLACE VIEW simple_view AS SELECT id, name, score FROM simple")
        }
    }

    @AfterTest
    fun teardown() {
        transaction {
            exec("DROP VIEW IF EXISTS simple_view")
            SchemaUtils.drop(SimpleTable)
        }
    }

    @Test
    fun `view constructs successfully`() {
        val columnNames = SimpleView.columns.map { it.name }
        assertTrue("name" in columnNames)
        assertTrue("score" in columnNames)
    }

    @Test
    fun `map reads data from view`() {
        transaction {
            SimpleTable.insert {
                it[name] = "alice"
                it[score] = 42
            }

            val row = SimpleView.selectAll().single()
            val data = SimpleView.map(row)

            assertEquals("alice", data.name)
            assertEquals(42, data.score)
        }
    }

    @Test
    fun `map does not link to database`() {
        transaction {
            SimpleTable.insert {
                it[name] = "bob"
                it[score] = 99
            }

            val row = SimpleView.selectAll().single()
            val data = SimpleView.map(row)

            assertNull(data.idOrNull)
        }
    }

    @Test
    fun `map reads multiple rows`() {
        transaction {
            SimpleTable.insert {
                it[name] = "alice"
                it[score] = 42
            }
            SimpleTable.insert {
                it[name] = "bob"
                it[score] = 99
            }

            val rows = SimpleView.selectAll().toList()
            val data = rows.map { SimpleView.map(it) }

            assertEquals(2, data.size)
            val names = data.map { it.name }.toSet()
            assertTrue("alice" in names)
            assertTrue("bob" in names)
        }
    }
}
