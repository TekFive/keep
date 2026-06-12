package org.tekfive.keep.ext

import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.array.ArrayOverlapOp
import org.tekfive.keep.array.intersects
import org.tekfive.keep.data.enumIntersects
import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.dataEnumList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private enum class Priority(override val id: Int, override val displayName: String) : DataEnum {
    LOW(1, "Low"),
    MEDIUM(2, "Medium"),
    HIGH(3, "High"),
}

private object OverlapTestTable : Table("overlap_test") {
    val priorities = dataEnumList<Priority>("priority_ids")
    val optionalPriorities = dataEnumList<Priority>("optional_priority_ids").nullable()
    val counts = array<Int>("counts")
    val referenceIds = array<Long>("reference_ids")
    val optionalReferenceIds = array<Long>("optional_reference_ids").nullable()
}

/**
 * A bare named expression — real [org.jetbrains.exposed.v1.core.Column]s need a
 * transaction in context to render their identifier, which these pure in-memory
 * tests deliberately avoid. [ArrayOverlapOp] never reads [columnType].
 */
private class StubColumn<T>(private val name: String) : ExpressionWithColumnType<T>() {
    override val columnType: IColumnType<T & Any> get() = error("not used by ArrayOverlapOp")

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder { +name }
    }
}

private fun renderSql(op: Op<Boolean>): String {
    val builder = QueryBuilder(prepared = false)
    op.toQueryBuilder(builder)
    return builder.toString()
}

class ArrayOverlapOpTest {

    @Test
    fun `enumIntersects produces ArrayOverlapOp`() {
        val op = OverlapTestTable.priorities enumIntersects listOf(Priority.LOW, Priority.HIGH)
        assertIs<ArrayOverlapOp>(op)
    }

    @Test
    fun `enumIntersects works on nullable columns`() {
        val op = OverlapTestTable.optionalPriorities enumIntersects listOf(Priority.MEDIUM)
        assertIs<ArrayOverlapOp>(op)
    }

    @Test
    fun `int intersects produces ArrayOverlapOp`() {
        val op = OverlapTestTable.counts intersects listOf(2, 5)
        assertIs<ArrayOverlapOp>(op)
    }

    @Test
    fun `long intersects produces ArrayOverlapOp for plain and nullable columns`() {
        assertIs<ArrayOverlapOp>(OverlapTestTable.referenceIds intersects listOf(7L))
        assertIs<ArrayOverlapOp>(OverlapTestTable.optionalReferenceIds intersects listOf(42L))
    }

    @Test
    fun `enumIntersects renders enum ids with an integer array cast`() {
        val op = StubColumn<List<Priority>>("priority_ids") enumIntersects listOf(Priority.LOW, Priority.HIGH)
        assertEquals("priority_ids && ARRAY[1,3]::integer[]", renderSql(op))
    }

    @Test
    fun `int intersects renders an integer array cast`() {
        val op = StubColumn<List<Int>>("counts") intersects listOf(2, 5)
        assertEquals("counts && ARRAY[2,5]::integer[]", renderSql(op))
    }

    @Test
    fun `long intersects renders a bigint array cast`() {
        val op = StubColumn<List<Long>>("reference_ids") intersects listOf(7L, 9_000_000_000L)
        assertEquals("reference_ids && ARRAY[7,9000000000]::bigint[]", renderSql(op))
    }
}
