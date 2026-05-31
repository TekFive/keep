package org.tekfive.keep.array

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.tekfive.keep.data.DataEnum

/**
 * PostgreSQL `&&` (array overlap) operator — returns true if the two arrays
 * share any elements.
 */
class ArrayOverlapOp(
    private val column: Expression<*>,
    private val values: List<Int>,
) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder {
            append(column)
            append(" && ARRAY[")
            append(values.joinToString(","))
            append("]::integer[]")
        }
    }
}

