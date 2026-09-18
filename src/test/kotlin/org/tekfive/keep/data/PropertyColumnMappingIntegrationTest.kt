package org.tekfive.keep.data

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PropertyMappedUuidData(val label: String, var count: Int, var items: List<JsonObject>) : UuidData()

object PropertyMappedUuidTable : UuidDataTable<PropertyMappedUuidData>("property_mapped_uuid") {
    val labelColumn = column(PropertyMappedUuidData::label, name = "stored_label").uniqueIndex()
    val countColumn = column(PropertyMappedUuidData::count).default(0)
    val itemsColumn = column(PropertyMappedUuidData::items)
}

class PropertyMappedBackingData(var _state: String) : Data() {
    var state: String
        get() = _state
        set(value) { _state = value }
}

object PropertyMappedBackingTable : DataTable<PropertyMappedBackingData>("property_mapped_backing") {
    val stateColumn = column(PropertyMappedBackingData::state)
}

class PropertyColumnMappingIntegrationTest {
    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction { SchemaUtils.create(PropertyMappedTable, PropertyMappedUuidTable, PropertyMappedBackingTable) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(PropertyMappedBackingTable, PropertyMappedUuidTable, PropertyMappedTable) }
    }

    @Test
    fun `long data CRUD and selective updates use bound properties`() = transaction {
        val data = PropertyMappedTable.create(PropertyMappedData("label", 3, "note", true))
        val loaded = PropertyMappedTable.getById(data.id)
        assertEquals("label", loaded.label)
        assertEquals(3, loaded.count)
        assertEquals("note", loaded.comment)

        loaded.count = 4
        loaded.comment = null
        PropertyMappedTable.update(loaded, PropertyMappedTable.comment)
        assertEquals(setOf("comment"), loaded.dirtyProperties)
        assertEquals("note", PropertyMappedTable.getById(data.id).comment)
        PropertyMappedTable.save(loaded)
        assertFalse(loaded.isDirty)
        val updated = PropertyMappedTable.getById(data.id)
        assertEquals(4, updated.count)
        assertNull(updated.comment)

        PropertyMappedTable.delete(loaded)
        assertNull(PropertyMappedTable.findById(data.id))
    }

    @Test
    fun `uuid data CRUD and selective updates use bound properties`() = transaction {
        val data = PropertyMappedUuidTable.create(
            PropertyMappedUuidData("label", 3, listOf(json { "value" set "initial" })),
        )
        val loaded = PropertyMappedUuidTable.getById(data.id)
        assertEquals("label", loaded.label)
        assertEquals("initial", loaded.items.single().string("value"))

        loaded.count = 4
        loaded.items = listOf(json { "value" set "updated" })
        PropertyMappedUuidTable.update(loaded, PropertyMappedUuidTable.countColumn)
        assertEquals(setOf("items"), loaded.dirtyProperties)
        assertEquals("initial", PropertyMappedUuidTable.getById(data.id).items.single().string("value"))
        PropertyMappedUuidTable.save(loaded)
        assertFalse(loaded.isDirty)
        val updated = PropertyMappedUuidTable.getById(data.id)
        assertEquals(4, updated.count)
        assertEquals("updated", updated.items.single().string("value"))

        PropertyMappedUuidTable.delete(loaded)
        assertNull(PropertyMappedUuidTable.findById(data.id))
    }

    @Test
    fun `selective updates snapshot the bound getter for underscore backing properties`() = transaction {
        val data = PropertyMappedBackingTable.create(PropertyMappedBackingData("initial"))
        val loaded = PropertyMappedBackingTable.getById(data.id)
        assertEquals("initial", loaded.state)
        loaded.state = "updated"
        PropertyMappedBackingTable.update(loaded, PropertyMappedBackingTable.stateColumn)
        assertFalse(loaded.isDirty)
        assertEquals("updated", PropertyMappedBackingTable.getById(data.id).state)
    }
}
