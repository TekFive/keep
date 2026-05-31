package org.tekfive.keep.array

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder

/**
 * PostgreSQL `= ANY(array_column)` operator — returns true if the scalar value
 * is contained in the array column.
 *
 * Generates: `? = ANY(column)` where `?` is a bound parameter.
 */
class ArrayContainsOp<T>(
    private val column: Expression<*>,
    private val value: T,
    private val sqlType: String,
) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder {
            registerArgument(resolveColumnType(sqlType), value)
            append(" = ANY(")
            append(column)
            append(")")
        }
    }
}

/**
 * Returns true when [value] is contained in this array column.
 *
 * Generates: `? = ANY(column)` — the PostgreSQL idiom for scalar-in-array membership.
 *
 * Works on both nullable and non-nullable array columns.
 *
 * Usage: `WorkflowRevisionTable.serviceReferences includes serviceId`
 */
infix fun ExpressionWithColumnType<out List<Long>?>.includes(value: Long): Op<Boolean> =
    ArrayContainsOp(this, value, "bigint")

/**
 * Returns true when [value] is contained in this array column.
 */
infix fun ExpressionWithColumnType<out List<Int>?>.includes(value: Int): Op<Boolean> =
    ArrayContainsOp(this, value, "integer")

/**
 * Returns true when [value] is contained in this array column.
 */
infix fun ExpressionWithColumnType<out List<String>?>.includes(value: String): Op<Boolean> =
    ArrayContainsOp(this, value, "text")

private fun resolveColumnType(sqlType: String): org.jetbrains.exposed.v1.core.IColumnType<out Any> {
    return when (sqlType) {
        "bigint" -> org.jetbrains.exposed.v1.core.LongColumnType()
        "integer" -> org.jetbrains.exposed.v1.core.IntegerColumnType()
        "text" -> org.jetbrains.exposed.v1.core.TextColumnType()
        else -> throw IllegalArgumentException("Unsupported SQL type: $sqlType")
    }
}
