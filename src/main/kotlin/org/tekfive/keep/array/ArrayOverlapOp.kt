package org.tekfive.keep.array

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder

/**
 * PostgreSQL `&&` (array overlap) operator — returns true if the two arrays
 * share any elements.
 *
 * Generates: `column && ARRAY[v1, v2, ...]::<castType>[]`
 *
 * [castType] is the PostgreSQL element type used for the array literal's cast
 * and must match the column's element type (`integer` for INTEGER[] columns,
 * `bigint` for BIGINT[] columns). Values are numeric and rendered as literals.
 */
class ArrayOverlapOp(
    private val column: Expression<*>,
    private val values: List<Number>,
    private val castType: String = "integer",
) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder {
            append(column)
            append(" && ARRAY[")
            append(values.joinToString(","))
            append("]::$castType[]")
        }
    }
}

/**
 * PostgreSQL array overlap for an INTEGER[] column — true when the column
 * contains any of [values].
 */
@JvmName("intersectsInt")
infix fun ExpressionWithColumnType<out List<Int>?>.intersects(values: List<Int>): Op<Boolean> {
    return ArrayOverlapOp(this, values, "integer")
}

/**
 * PostgreSQL array overlap for a BIGINT[] column — true when the column
 * contains any of [values].
 */
@JvmName("intersectsLong")
infix fun ExpressionWithColumnType<out List<Long>?>.intersects(values: List<Long>): Op<Boolean> {
    return ArrayOverlapOp(this, values, "bigint")
}
