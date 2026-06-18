package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetArrayColumnTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction {
            SchemaUtils.create(StringSetTable)
        }
    }

    @AfterTest
    fun teardown() {
        transaction {
            SchemaUtils.drop(StringSetTable)
        }
    }

    @Test
    fun `setArray stores and retrieves basic values`() {
        transaction {
            val data = StringSetData("row", linkedSetOf("clinical", "urgent"))
            StringSetTable.create(data)

            val row = StringSetTable.selectAll().where { StringSetTable.id eq data.id }.single()
            val loaded = StringSetTable.map(row)

            assertEquals(setOf("clinical", "urgent"), loaded.tags)
        }
    }

    @Test
    fun `setArray handles empty set`() {
        transaction {
            val data = StringSetData("empty", emptySet())
            StringSetTable.create(data)

            val row = StringSetTable.selectAll().where { StringSetTable.id eq data.id }.single()
            val loaded = StringSetTable.map(row)

            assertTrue(loaded.tags.isEmpty())
        }
    }
}
