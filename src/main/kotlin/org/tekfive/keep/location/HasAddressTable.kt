package org.tekfive.keep.location

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.data.ColumnGroup
import org.tekfive.keep.data.dataEnum
import org.tekfive.keep.utils.ColumnValueMapper

class AddressColumnGroup(table: Table) : ColumnGroup<Address> {
    val street: Column<String> = table.street()
    val city: Column<String> = table.city()
    val state: Column<State> = table.state()
    val zip: Column<String> = table.zip()

    override val columns = listOf(street, city, state, zip)

    override fun map(row: ResultRow) = Address(
        street = row[street],
        city = row[city],
        state = row[state],
        zip = row[zip],
    )

    override fun mapColumns(value: Address, statement: ColumnValueMapper) {
        statement[street] = value.street ?: error("No street in address.")
        statement[city] = value.city ?: error("No city in address.")
        statement[state] = value.state ?: error("No state in address.")
        statement[zip] = value.zip ?: error("No zip in address.")
    }
}

class OptionalAddressColumnGroup(table: Table) : ColumnGroup<Address> {
    val street: Column<String?> = table.optionalStreet()
    val city: Column<String?> = table.optionalCity()
    val state: Column<State?> = table.optionalState()
    val zip: Column<String?> = table.optionalZip()

    override val columns = listOf(street, city, state, zip)

    override fun map(row: ResultRow): Address {
        return Address(row[street], row[city], row[state], row[zip])
    }

    override fun mapColumns(value: Address, statement: ColumnValueMapper) {
        statement[street] = value.street?.ifBlank { null }
        statement[city] = value.city?.ifBlank { null }
        statement[state] = value.state
        statement[zip] = value.zip?.ifBlank { null }
    }
}

fun Table.address(): AddressColumnGroup = AddressColumnGroup(this)

fun Table.optionalAddress(): OptionalAddressColumnGroup = OptionalAddressColumnGroup(this)

fun Table.street(name: String = "street"): Column<String> = varchar(name, 255)

fun Table.city(name: String = "city"): Column<String> = varchar(name, 100)

fun Table.state(name: String = "state_id"): Column<State> = dataEnum(name)

fun Table.zip(name: String = "zip"): Column<String> = varchar(name, 20)

fun Table.optionalStreet(name: String = "street"): Column<String?> = varchar(name, 255).nullable()

fun Table.optionalCity(name: String = "city"): Column<String?> = varchar(name, 100).nullable()

fun Table.optionalState(name: String = "state_id"): Column<State?> = dataEnum<State>(name).nullable()

fun Table.optionalZip(name: String = "zip"): Column<String?> = varchar(name, 20).nullable()
