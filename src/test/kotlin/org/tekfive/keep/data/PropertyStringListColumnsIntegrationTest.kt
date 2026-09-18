package org.tekfive.keep.data

import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PropertyStringListModel(var tags: List<String>, var optionalTags: List<String>?) : Data()

object PropertyStringListTable : DataTable<PropertyStringListModel>("property_string_lists") {
    val tagColumn = column(PropertyStringListModel::tags, maxSize = 4)
    val optionalTagColumn = column(PropertyStringListModel::optionalTags, maxSize = 4)
}

class PropertyStringListColumnsIntegrationTest {
    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction { SchemaUtils.create(PropertyStringListTable) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(PropertyStringListTable) }
    }

    @Test
    fun `round trips bounded strings without limiting the number of elements`() {
        val tags = listOf("four", "猫猫猫猫", "", "a", "b", "c")
        transaction {
            PropertyStringListTable.create(PropertyStringListModel(tags, tags))
        }
        transaction {
            val loaded = PropertyStringListTable.selectAll().single().let(PropertyStringListTable::map)
            assertEquals(tags, loaded.tags)
            assertEquals(tags, loaded.optionalTags)
            loaded.tags = emptyList()
            loaded.optionalTags = null
            PropertyStringListTable.save(loaded)
        }
        transaction {
            val loaded = PropertyStringListTable.selectAll().single().let(PropertyStringListTable::map)
            assertEquals(emptyList(), loaded.tags)
            assertNull(loaded.optionalTags)
        }
    }

    @Test
    fun `PostgreSQL rejects oversized elements in both required and nullable arrays`() {
        for (values in listOf("ARRAY['valid'], NULL", "ARRAY['ok'], ARRAY['valid']")) {
            val error = assertFailsWith<ExposedSQLException> {
                transaction {
                    maxAttempts = 1
                    exec("INSERT INTO property_string_lists (tags, optional_tags) VALUES ($values)")
                }
            }
            assertEquals("22001", error.sqlState)
        }
    }
}
