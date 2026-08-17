package org.tekfive.keep.data

import java.util.UUID

/** Association table joining two UUID-ID [UuidDataTable]s. */
open class UuidDataJoinTable<A : UuidData, B : UuidData>(
    name: String,
    tableA: UuidDataTable<A>,
    tableB: UuidDataTable<B>,
    columnAName: String = "${tableA.tableName}_id",
    columnBName: String = "${tableB.tableName}_id",
) : TypedDataJoinTable<UUID, A, UUID, B>(name, tableA, tableB, columnAName, columnBName)
