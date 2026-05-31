package org.tekfive.keep.job.db

import java.sql.PreparedStatement
import java.util.concurrent.atomic.AtomicInteger

sealed interface QueryNode {

    fun toParameterizedSql(columnName: String): String = when (this) {
        is Condition -> operator.toParameterizedSql(columnName)
        is Group -> {
            val joined = children.joinToString(" ${type.name} ") { it.toParameterizedSql(columnName) }
            "($joined)"
        }
    }

    fun addValue(index: AtomicInteger, statement: PreparedStatement) {
        when (this) {
            is Condition -> operator.addValue(index, statement)
            is Group -> {
                for (child in children) {
                    child.addValue(index, statement)
                }
            }
        }
    }

    data class Condition(val operator: JsonPathOperator) : QueryNode

    data class Group(
        val type: LogicalOperator,
        val children: List<QueryNode>
    ) : QueryNode
}