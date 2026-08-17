package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/** Association table that can join KEEP tables using any supported primary-key types. */
open class TypedDataJoinTable<
    AID : Any,
    A : IdentifiedData<AID>,
    BID : Any,
    B : IdentifiedData<BID>,
>(
    name: String,
    val tableA: TypedDataTuple<AID, A>,
    val tableB: TypedDataTuple<BID, B>,
    columnAName: String = "${tableA.tableName}_id",
    columnBName: String = "${tableB.tableName}_id",
) : Table(name) {

    val aId: Column<AID> = reference(columnAName, tableA.id)
    val bId: Column<BID> = reference(columnBName, tableB.id)

    override val primaryKey = PrimaryKey(aId, bId)

    fun joinA() = innerJoin(tableA)

    fun joinB() = innerJoin(tableB)

    fun joinBoth() = innerJoin(tableA).innerJoin(tableB)

    fun mapA(row: ResultRow): A = tableA.map(row)

    fun mapB(row: ResultRow): B = tableB.map(row)

    fun mapBoth(row: ResultRow): Pair<A, B> = tableA.map(row) to tableB.map(row)
}
