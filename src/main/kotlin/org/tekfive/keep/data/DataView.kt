package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column

/**
 * Read-only table class for database views. Extends [DataTuple] with reflection-based
 * mapping from result rows to [Data] objects, but provides no write operations.
 *
 * ```
 * class UserSummaryData(val name: String, var totalOrders: Int) : Data()
 *
 * object UserSummaryView : DataView<UserSummaryData>("user_summary_view") {
 *     val name = varchar("name", 255)
 *     val totalOrders = integer("total_orders")
 * }
 * ```
 */
abstract class DataView<D : Data>(name: String) : DataTuple<D>(name, managedColumns = setOf("id")) {
    override val id: Column<Long> = long("id")
}
