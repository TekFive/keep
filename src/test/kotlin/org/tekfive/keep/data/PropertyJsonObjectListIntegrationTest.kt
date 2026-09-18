package org.tekfive.keep.data

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PropertyJsonObjectListModel(
    var items: List<JsonObject>,
    var optionalItems: List<JsonObject>?,
) : Data()

object PropertyJsonObjectListTable : DataTable<PropertyJsonObjectListModel>("property_json_object_lists") {
    val items = column(PropertyJsonObjectListModel::items)
    val optionalItems = column(PropertyJsonObjectListModel::optionalItems)
}

class PropertyJsonObjectListIntegrationTest {
    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction {
            SchemaUtils.create(PropertyJsonObjectListTable)
        }
    }

    @AfterTest
    fun teardown() {
        transaction {
            SchemaUtils.drop(PropertyJsonObjectListTable)
        }
    }

    @Test
    fun `creates reads and updates JSON object list properties`() {
        val objects = listOf(json { "name" set "first" }, json { "name" set "second" })
        transaction {
            PropertyJsonObjectListTable.create(PropertyJsonObjectListModel(objects, objects))
        }
        transaction {
            val loaded = PropertyJsonObjectListTable.selectAll().single().let(PropertyJsonObjectListTable::map)
            assertEquals(listOf("first", "second"), loaded.items.map { it.string("name") })
            assertEquals(listOf("first", "second"), loaded.optionalItems?.map { it.string("name") })
            loaded.items = emptyList()
            loaded.optionalItems = null
            PropertyJsonObjectListTable.update(loaded)
        }
        transaction {
            val loaded = PropertyJsonObjectListTable.selectAll().single().let(PropertyJsonObjectListTable::map)
            assertEquals(emptyList(), loaded.items)
            assertNull(loaded.optionalItems)
        }
    }
}
