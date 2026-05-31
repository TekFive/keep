package org.tekfive.keep.text

import org.jetbrains.exposed.v1.core.ComparisonOp
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Function
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.stringParam

/**
 * PostgreSQL `ILIKE` operator — case-insensitive pattern matching.
 */
class ILikeEscapeOp(
    expr1: Expression<*>,
    expr2: Expression<*>,
    val escapeChar: Char?,
) : ComparisonOp(expr1, expr2, "ILIKE") {

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        super.toQueryBuilder(queryBuilder)
        if (escapeChar != null) {
            queryBuilder.apply {
                +" ESCAPE "
                +stringParam(escapeChar.toString())
            }
        }
    }
}

/**
 * Converts a user-facing search pattern to a SQL LIKE pattern.
 *
 * - If the pattern contains `*`, each `*` is replaced with `%` (user-controlled wildcards).
 * - Otherwise, the pattern is wrapped with `%` on both sides for a contains match.
 */
fun toILikePattern(pattern: String): String {
    return if ('*' in pattern) {
        pattern.replace('*', '%')
    } else {
        "%$pattern%"
    }
}

/**
 * Case-insensitive LIKE (PostgreSQL `ILIKE`).
 *
 * Automatically adds `%` wildcards for a contains match unless the pattern includes
 * `*`, in which case each `*` is converted to `%`.
 *
 * Usage: `TalentsTable.name ilike "john"` — matches "John Doe", "johnny", etc.
 * Usage: `TalentsTable.name ilike "john*"` — matches "John Doe" but not "Big John"
 */
infix fun <T : String?> ExpressionWithColumnType<T>.ilike(pattern: String): ILikeEscapeOp =
    ILikeEscapeOp(this, stringParam(toILikePattern(pattern)), escapeChar = null)

/**
 * Case-insensitive LIKE (PostgreSQL `ILIKE`) with a [LikePattern] for escape-char support.
 *
 * This overload does **not** apply automatic wildcarding — the caller controls the pattern.
 *
 * Usage: `TalentsTable.name ilike LikePattern("%test\\%value%", '\\')`
 */
infix fun <T : String?> ExpressionWithColumnType<T>.ilike(pattern: LikePattern): ILikeEscapeOp =
    ILikeEscapeOp(this, stringParam(pattern.pattern), escapeChar = pattern.escapeChar)

/**
 * Case-insensitive LIKE on an array column using `array_to_string`.
 *
 * Automatically adds `%` wildcards for a contains match unless the pattern includes
 * `*`, in which case each `*` is converted to `%`.
 *
 * Generates: `array_to_string(column, ',') ILIKE pattern`
 *
 * Usage: `TalentsTable.emailAddresses arrayILike "john"` — matches any email containing "john"
 */
infix fun <T : List<String>?> ExpressionWithColumnType<T>.arrayILike(pattern: String): ILikeEscapeOp =
    ILikeEscapeOp(ArrayToStringOp(this), stringParam(toILikePattern(pattern)), escapeChar = null)

/**
 * Wraps a column with PostgreSQL `array_to_string(column, ',')`.
 */
class ArrayToStringOp(
    private val expr: Expression<*>,
) : Function<String>(VarCharColumnType(Int.MAX_VALUE)) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder {
            append("array_to_string(")
            append(expr)
            append(", ")
            append(stringParam(","))
            append(")")
        }
    }
}
