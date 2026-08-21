package org.tekfive.keep.paged

import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.ResultRow
import org.tekfive.kviash.http.HttpRequestParameters

/**
 * A [PagedQuery] that maps result rows to typed DTO items instead of JFK JSON objects.
 *
 * Subclasses configure returned columns, search, filters, and sorting exactly as they do for
 * [PagedQuery]. Registered return columns continue to define the names accepted by the `sort`
 * request parameter. Implement [mapRow] to create one DTO for each selected row, then call
 * [executePage] inside a database transaction.
 */
abstract class DtoPagedQuery<T : Any>(
    table: ColumnSet,
    parameters: HttpRequestParameters,
) : PagedQuery(table, parameters) {

    /** Maps one selected row from [source] to a page item. */
    protected abstract fun mapRow(row: ResultRow): T

    /** Executes the configured query and returns typed paging metadata and DTO items. */
    fun executePage(): DtoPage<T> = executePageRows(::mapRow)
}

/** One executed page containing paging metadata and typed DTO items. */
data class DtoPage<T>(
    val total: Int,
    val page: Int,
    val size: Int,
    val data: List<T>,
)
