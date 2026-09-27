package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.tekfive.keep.schema.PostgresView
import org.tekfive.keep.schema.PostgresViewDefinition

/**
 * Read-only table class for database views. Extends [DataTuple] with reflection-based
 * mapping from result rows to [Data] objects, but provides no write operations. Each view must
 * supply [viewDefinition]; register the view object directly in KeepSchema.views.
 *
 * ```
 * class UserSummaryData(val name: String) : Data()
 *
 * object UserSummaryView : DataView<UserSummaryData>("user_summary_view") {
 *     val name = varchar("name", 255)
 *     override val viewDefinition by lazy {
 *         view(UsersTable) { UsersTable.select(UsersTable.id, UsersTable.name) }
 *     }
 * }
 * ```
 */
abstract class DataView<D : Data>(name: String) : DataTuple<D>(name, managedColumns = setOf("id")), PostgresView {
    override val id: Column<Long> = long("id")

    /** Defines this view with `view(sources...) { query }`; register the view object in KeepSchema.views. */
    abstract override val viewDefinition: PostgresViewDefinition
}
