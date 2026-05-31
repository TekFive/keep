package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * A join (association) table linking two [DataTable]s via foreign key columns. The table has
 * exactly two columns — one FK per side — and a composite primary key over both.
 *
 * Example:
 * ```
 * object UserRoles : DataJoinTable<UserData, RoleData>(
 *     "user_roles", UsersTable, RolesTable
 * )
 * ```
 *
 * Convenience methods are provided for:
 * - **Joins**: [joinA], [joinB], [joinBoth] return Exposed [Join][org.jetbrains.exposed.v1.core.Join]
 *   objects with FK-based join conditions already resolved.
 * - **Mapping**: [mapA], [mapB], [mapBoth] construct [Data] instances from a [ResultRow] produced
 *   by a join query, delegating to each side's [DataTable.map].
 *
 * @param name       SQL table name
 * @param tableA     the first DataTable in the relationship
 * @param tableB     the second DataTable in the relationship
 * @param columnAName  SQL column name for the FK to [tableA] (defaults to `<tableA name>_id`)
 * @param columnBName  SQL column name for the FK to [tableB] (defaults to `<tableB name>_id`)
 */
open class DataJoinTable<A : Data, B : Data>(
    name: String,
    val tableA: DataTable<A>,
    val tableB: DataTable<B>,
    columnAName: String = "${tableA.tableName}_id",
    columnBName: String = "${tableB.tableName}_id",
) : Table(name) {

    /** Foreign key column referencing [tableA]'s id. */
    val aId: Column<Long> = long(columnAName).references(tableA.id)

    /** Foreign key column referencing [tableB]'s id. */
    val bId: Column<Long> = long(columnBName).references(tableB.id)

    override val primaryKey = PrimaryKey(aId, bId)

    // -- Join helpers ---------------------------------------------------------

    /** This join table inner-joined with [tableA]. */
    fun joinA() = innerJoin(tableA)

    /** This join table inner-joined with [tableB]. */
    fun joinB() = innerJoin(tableB)

    /** This join table inner-joined with both [tableA] and [tableB]. */
    fun joinBoth() = innerJoin(tableA).innerJoin(tableB)

    // -- Mapping helpers ------------------------------------------------------

    /** Construct a [Data] instance of type A from a join query [ResultRow]. */
    fun mapA(row: ResultRow): A = tableA.map(row)

    /** Construct a [Data] instance of type B from a join query [ResultRow]. */
    fun mapB(row: ResultRow): B = tableB.map(row)

    /** Construct both [Data] instances from a join query [ResultRow]. */
    fun mapBoth(row: ResultRow): Pair<A, B> = tableA.map(row) to tableB.map(row)
}
