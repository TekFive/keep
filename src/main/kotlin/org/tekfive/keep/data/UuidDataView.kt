package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.java.javaUUID
import java.util.UUID

/** Read-only database view mapped to [UuidData]. */
abstract class UuidDataView<D : UuidData>(name: String) :
    UuidDataTuple<D>(name, managedColumns = setOf("id")) {
    override val id: Column<UUID> = javaUUID("id")
}
