package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.location.Address
import org.tekfive.keep.location.AddressColumnGroup
import org.tekfive.keep.location.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PropertyAddressData(var address: Address) : Data()

object PropertyAddressTable : DataTable<PropertyAddressData>("property_addresses") {
    val address = column(PropertyAddressData::address, AddressColumnGroup(this))
}

class PropertyColumnGroupTest {
    @Test
    fun `preserves the concrete group and its existing columns`() {
        val table = object : Table("property_group") {}
        val group = AddressColumnGroup(table)

        val address: AddressColumnGroup = table.column(PropertyAddressData::address, group)

        assertSame(group, address)
        assertEquals(listOf("street", "city", "state_id", "zip"), table.columns.map { it.name })
        assertEquals(group.columns, table.columns)
    }

    @Test
    fun `maps property column groups through data tables for reads inserts and updates`() {
        val address = Address("123 Main St", "Springfield", State.IL, "62701")
        val data = PropertyAddressData(address)
        val group = PropertyAddressTable.address
        val expected = mapOf<Column<*>, Any?>(
            group.street to address.street,
            group.city to address.city,
            group.state to address.state,
            group.zip to address.zip,
        )

        for (insert in listOf(true, false)) {
            val mapper = TestColumnValueMapper()
            PropertyAddressTable.mapColumns(data, mapper, insert)
            assertEquals(expected, mapper.values)
        }

        val row = ResultRow.createAndFillValues(buildMap {
            putAll(expected)
            put(group.state, State.IL.id)
            put(PropertyAddressTable.id, 1L)
        })
        assertEquals(address, PropertyAddressTable.map(row).address)
    }

    @Test
    fun `rejects a group created on another table`() {
        val table = object : Table("property_group_target") {}
        val otherTable = object : Table("property_group_other") {}

        val error = assertFailsWith<IllegalArgumentException> {
            table.column(PropertyAddressData::address, AddressColumnGroup(otherTable))
        }

        assertEquals(
            "Column group for 'address' must contain only columns from table 'property_group_target'",
            error.message,
        )
    }
}
