package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.migration.dynamic.CreateIndex
import org.tekfive.keep.migration.dynamic.IndexDefinition
import org.tekfive.keep.migration.dynamic.QualifiedName
import org.tekfive.keep.migration.dynamic.SqlExpression

/** A column or trusted SQL expression, with explicit PostgreSQL ordering. */
sealed interface PostgresIndexKey {
    val order: SortOrder?
    data class ColumnKey(val column: Column<*>, override val order: SortOrder? = null) : PostgresIndexKey
    data class ExpressionKey(val expression: SqlExpression, override val order: SortOrder? = null) : PostgresIndexKey
}

fun Column<*>.indexKey(order: SortOrder? = null): PostgresIndexKey = PostgresIndexKey.ColumnKey(this, order)

/** Desired index structure. Expressions and predicates use unqualified column names. */
class PostgresIndexDefinition internal constructor(
    override val name: String,
    override val table: Table,
    val keys: List<PostgresIndexKey>,
    val unique: Boolean,
    val method: String,
    val include: List<Column<*>>,
    val predicate: SqlExpression?,
    val nullsNotDistinct: Boolean,
) : PostgresTableObject {
    internal fun definition(context: PostgresRenderContext): IndexDefinition {
        require(!nullsNotDistinct || context.targetVersion.major >= 15) { "NULLS NOT DISTINCT requires PostgreSQL 15+" }
        return IndexDefinition(
            QualifiedName(name, context.schemaName),
            QualifiedName(table.tableName.substringAfterLast('.').removeSurrounding("\""), context.schemaName),
            keys.map { key ->
                val expression = when (key) {
                    is PostgresIndexKey.ColumnKey -> context.identifier(key.column.name)
                    is PostgresIndexKey.ExpressionKey -> "(${key.expression.text})"
                }
                SqlExpression(expression + (key.order?.let { " ${it.name.replace('_', ' ')}" } ?: ""))
            }, unique, method, include.map { it.nameUnquoted() }, predicate, nullsNotDistinct,
        )
    }
    override fun createStatements(context: PostgresRenderContext): List<String> = listOf(CreateIndex(definition(context)).toSql())
}
