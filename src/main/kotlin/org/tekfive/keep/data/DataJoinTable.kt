package org.tekfive.keep.data

/** Association table joining two Long-ID [DataTable]s. */
open class DataJoinTable<A : Data, B : Data>(
    name: String,
    tableA: DataTable<A>,
    tableB: DataTable<B>,
    columnAName: String = "${tableA.tableName}_id",
    columnBName: String = "${tableB.tableName}_id",
) : TypedDataJoinTable<Long, A, Long, B>(name, tableA, tableB, columnAName, columnBName)
