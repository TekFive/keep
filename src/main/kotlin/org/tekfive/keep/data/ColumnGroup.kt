package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.tekfive.keep.utils.ColumnValueMapper

/**
 * A composite column mapping that maps a single [Data] constructor property to multiple table columns.
 *
 * Implementations create columns on the target table and define how to read/write the composite
 * value from/to those columns. [DataTuple] and [DataTable] automatically discover [ColumnGroup]
 * properties alongside regular [Column] properties.
 *
 * ```
 * class AddressColumnGroup(table: Table) : ColumnGroup<Address> {
 *     val street = table.varchar("street", 255)
 *     val city = table.varchar("city", 100)
 *     ...
 *     override val columns = listOf(street, city, ...)
 *     override fun map(row: ResultRow) = Address(row[street], row[city], ...)
 *     override fun mapColumns(value: Address, statement: ColumnValueMapper) { ... }
 * }
 *
 * object Locations : DataTable<LocationData>("locations") {
 *     val name = varchar("name", 255)
 *     val address = AddressColumnGroup(this)
 * }
 * ```
 */
interface ColumnGroup<T> {
    val columns: List<Column<*>>
    fun map(row: ResultRow): T
    fun mapColumns(value: T, statement: ColumnValueMapper)
}
