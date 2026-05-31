package org.tekfive.keep.job.db

import java.sql.PreparedStatement
import java.util.concurrent.atomic.AtomicInteger

sealed interface JsonPathOperator {
    val path: List<String>

    fun toParameterizedSql(columnName: String): String {
        val targetField = "jsonb_extract_path_text($columnName, ${path.joinToString(", ") { "?" }})"

        return when (this) {
            is GTE -> "($targetField)::numeric >= ?"
            is GT -> "($targetField)::numeric > ?"
            is LTE -> "($targetField)::numeric <= ?"
            is LT -> "($targetField)::numeric < ?"
            is Equals -> "$targetField = ?"
            is Contains -> "($targetField)::jsonb ?? ?"
        }
    }

    fun addValue(index: AtomicInteger, statement: PreparedStatement) {
        path.forEach { key ->
            statement.setString(index.getAndIncrement(), key)
        }

        val rawValue = when (this) {
            is GTE -> value
            is GT -> value
            is LTE -> value
            is LT -> value
            is Equals -> value
            is Contains -> value
        }

        statement.setObject(index.getAndIncrement(), rawValue)
    }

    fun assertValidPath(path: List<String>) {
        require(path.isNotEmpty()) { "JSON path must contain at least one key." }
        require(path.none { it.isEmpty() }) { "JSON path keys must not be empty." }
    }

    fun assertValidNumber(value: Number) {
        require(value is Byte || value is Short || value is Int || value is Long || value is Float || value is Double) {"Number value must be Byte, Short, Int, Long, Float, or Double."}
    }

    data class GTE(override val path: List<String>, val value: Number) : JsonPathOperator {
        init {
            assertValidPath(path)
            assertValidNumber(value)
        }
    }
    data class GT(override val path: List<String>, val value: Number) : JsonPathOperator {
        init {
            assertValidPath(path)
            assertValidNumber(value)
        }
    }

    data class LTE(override val path: List<String>, val value: Number) : JsonPathOperator {
        init {
            assertValidPath(path)
            assertValidNumber(value)
        }
    }
    data class LT(override val path: List<String>, val value: Number) : JsonPathOperator {
        init {
            assertValidPath(path)
            assertValidNumber(value)
        }
    }
    data class Equals(override val path: List<String>, val value: Any) : JsonPathOperator {
        init {
            assertValidPath(path)
            if (value is Number) {
                assertValidNumber(value)
            } else {
                require(value is String) { "Value must be a Number or String"}
            }
        }
    }
    data class Contains(override val path: List<String>, val value: String) : JsonPathOperator {
        init {
            assertValidPath(path)
        }
    }
}
