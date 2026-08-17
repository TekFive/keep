package org.tekfive.keep.data

import java.util.UUID

/** Read/query tuple base for UUID primary keys. */
abstract class UuidDataTuple<D : UuidData>(
    name: String,
    managedColumns: Set<String> = emptySet(),
) : TypedDataTuple<UUID, D>(name, managedColumns)
