package org.tekfive.keep.array

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.tekfive.keep.data.DataEnum

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
infix fun ExpressionWithColumnType<out Collection<Int>?>.intersects(values: Collection<Int>): Op<Boolean> {
    return ArrayOverlapOp(this, values.toList(), "integer")
}

/**
 * PostgreSQL array overlap for a BIGINT[] column — true when the column
 * contains any of [values].
 */
@JvmName("intersectsLong")
infix fun ExpressionWithColumnType<out Collection<Long>?>.intersects(values: Collection<Long>): Op<Boolean> {
    return ArrayOverlapOp(this, values.toList(), "bigint")
}

/**
 * Returns true when this INTEGER[] enum column contains any of [values], matched by [DataEnum.id].
 * Supports nullable/non-nullable lists and sets. Empty input and SQL NULL never match.
 * Encrypted enum columns are not supported.
 */
@JvmName("intersectsDataEnum")
infix fun <E> ExpressionWithColumnType<out Collection<E>?>.intersects(values: Collection<E>): Op<Boolean>
    where E : Enum<E>, E : DataEnum =
    ArrayOverlapOp(this, values.map { it.id }, "integer")
