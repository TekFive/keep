package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.json.jsonArray
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.toJsonArray
import org.tekfive.jfk.toJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private object JsonbArrayTestTable : Table("jsonb_array_test") {
    val id = long("id").autoIncrement()
    val items = jsonArray("items")
    val optionalItems = jsonArray("optional_items").nullable()

    override val primaryKey = PrimaryKey(id)
}

class JsonbArrayColumnTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction {
            SchemaUtils.drop(JsonbArrayTestTable)
            SchemaUtils.create(JsonbArrayTestTable)
        }
    }

    @AfterTest
    fun teardown() {
        transaction {
            SchemaUtils.drop(JsonbArrayTestTable)
        }
    }

    @Test
    fun `stores and retrieves a json array of objects`() {
        val data = JsonArray(listOf(
            mapOf("name" to "Alice", "age" to 30).toJsonObject(),
            mapOf("name" to "Bob", "age" to 25).toJsonObject(),
        ))
        transaction {
            JsonbArrayTestTable.insert {
                it[items] = data
            }

            val row = JsonbArrayTestTable.selectAll().single()
            val result = row[JsonbArrayTestTable.items]
            assertEquals(2, result.size)
            assertEquals("Alice", (result.items[0] as JsonObject)["name"].string)
            assertEquals(25, (result.items[1] as JsonObject)["age"].int)
        }
    }

    @Test
    fun `stores and retrieves a json array of strings`() {
        val data = listOf("red", "green", "blue").toJsonArray()
        transaction {
            JsonbArrayTestTable.insert {
                it[items] = data
            }

            val row = JsonbArrayTestTable.selectAll().single()
            val result = row[JsonbArrayTestTable.items]
            assertEquals(listOf("red", "green", "blue"), result.toList())
        }
    }

    @Test
    fun `stores and retrieves an empty array`() {
        transaction {
            JsonbArrayTestTable.insert {
                it[items] = JsonArray(emptyList())
            }

            val row = JsonbArrayTestTable.selectAll().single()
            assertTrue(row[JsonbArrayTestTable.items].items.isEmpty())
        }
    }

    @Test
    fun `nullable column stores null`() {
        transaction {
            JsonbArrayTestTable.insert {
                it[items] = JsonArray(emptyList())
                it[optionalItems] = null
            }

            val row = JsonbArrayTestTable.selectAll().single()
            assertEquals(null, row[JsonbArrayTestTable.optionalItems])
        }
    }

    @Test
    fun `preserves object field values`() {
        val obj = mapOf(
            "street" to "123 Main St",
            "city" to "Springfield",
            "zip" to "62704",
        ).toJsonObject()
        val data = JsonArray(listOf(obj))
        transaction {
            JsonbArrayTestTable.insert {
                it[items] = data
            }

            val row = JsonbArrayTestTable.selectAll().single()
            val result = row[JsonbArrayTestTable.items].items[0] as JsonObject
            assertEquals("123 Main St", result["street"].string)
            assertEquals("Springfield", result["city"].string)
            assertEquals("62704", result["zip"].string)
        }
    }
}
