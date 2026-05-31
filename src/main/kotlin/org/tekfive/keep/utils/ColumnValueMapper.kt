package org.tekfive.keep.utils

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable

/**
 * Abstraction for mapping column-value pairs. Allows [DataTable.mapColumns][org.tekfive.keep.data.DataTable.mapColumns]
 * to be tested without a database connection by substituting a simple in-memory implementation.
 */
interface ColumnValueMapper {
    val updatedColumns: List<Column<*>>
    operator fun <S> set(column: Column<S>, value: S)
}

class FilteredUpdateStatement(
    val updateBuilder: UpdateBuilder<*>,
    val allowedColumns: Set<Column<*>> = setOf(),
) : ColumnValueMapper {

    override val updatedColumns: List<Column<*>>
        get() = _updatedColumns

    private val _updatedColumns = mutableListOf<Column<*>>()

    override operator fun <S> set(column: Column<S>, value: S) {
        if (allowedColumns.isEmpty() || allowedColumns.contains(column)) {
            updateBuilder[column] = value
            _updatedColumns.add(column)
        }
    }

    fun execute(transaction: Transaction): Int? {
        @Suppress("UNCHECKED_CAST")
        val builder = updateBuilder as UpdateBuilder<Int>
        return builder.toExecutable().execute(transaction as JdbcTransaction)
    }
}