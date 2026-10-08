package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Op
import java.util.UUID

/** A complete, expiring table snapshot for UUID IDs. See [TypedDatabaseTableCache]. */
open class UuidDatabaseTableCache<D : UuidData>(
    table: UuidDataTable<D>,
    cachePredicate: Op<Boolean>? = null,
) : TypedDatabaseTableCache<UUID, D>(table, cachePredicate)
