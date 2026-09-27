package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.tekfive.keep.schema.PostgresView
import org.tekfive.keep.schema.PostgresViewDefinition
import java.util.UUID

/** Read-only database view mapped to [UuidData], with a required, view-owned [viewDefinition]. */
abstract class UuidDataView<D : UuidData>(name: String) :
    UuidDataTuple<D>(name, managedColumns = setOf("id")), PostgresView {
    override val id: Column<UUID> = javaUUID("id")

    /** Defines this view with `view(sources...) { query }`; register the view object in KeepSchema.views. */
    abstract override val viewDefinition: PostgresViewDefinition
}
