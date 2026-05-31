package org.tekfive.keep.ext.postgres

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.text.CitextColumnType
import org.tekfive.keep.text.citext
import org.tekfive.keep.data.maxLength
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private object CitextTestTable : Table("citext_test") {
    val label = citext("label", 100)
    val notes = citext("notes")
}

class CitextColumnTypeTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction {
            exec("CREATE EXTENSION IF NOT EXISTS \"${CitextColumnType.Extension}\"")
            SchemaUtils.drop(CitextTestTable)
            SchemaUtils.create(CitextTestTable)
        }
    }

    @AfterTest
    fun teardown() {
        transaction {
            SchemaUtils.drop(CitextTestTable)
        }
    }

    @Test
    fun `sqlType returns CITEXT`() {
        assertEquals("CITEXT", CitextColumnType().sqlType())
    }

    @Test
    fun `citext stores and retrieves values`() {
        transaction {
            CitextTestTable.insert {
                it[label] = "Hello"
                it[notes] = "Some notes"
            }

            val row = CitextTestTable.selectAll().single()
            assertEquals("Hello", row[CitextTestTable.label])
            assertEquals("Some notes", row[CitextTestTable.notes])
        }
    }

    @Test
    fun `citext comparison is case insensitive`() {
        transaction {
            CitextTestTable.insert {
                it[label] = "Hello"
                it[notes] = "notes"
            }

            // Use raw SQL to confirm citext case-insensitive equality
            val count = exec("SELECT count(*) FROM citext_test WHERE label = 'HELLO'") { rs ->
                rs.next()
                rs.getLong(1)
            }

            assertEquals(1L, count)
        }
    }

    @Test
    fun `maxLength returns colLength for citext with length`() {
        assertEquals(100, CitextTestTable.label.maxLength)
    }

    @Test
    fun `maxLength returns max int for citext without length`() {
        assertEquals(Int.MAX_VALUE, CitextTestTable.notes.maxLength)
    }

    @Test
    fun `validation rejects values exceeding colLength`() {
        val type = CitextColumnType(5)
        assertFailsWith<IllegalArgumentException> {
            type.validateValueBeforeUpdate("toolong")
        }
    }

    @Test
    fun `validation accepts values within colLength`() {
        val type = CitextColumnType(5)
        type.validateValueBeforeUpdate("ok")
    }
}
