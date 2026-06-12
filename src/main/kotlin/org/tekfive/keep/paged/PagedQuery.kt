package org.tekfive.keep.paged

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.StringColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.JsonValue
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.json
import org.tekfive.jfk.jsonArray
import org.tekfive.keep.text.ilike
import org.tekfive.kviash.http.HttpRequestParameters

abstract class PagedQuery(
    val table: ColumnSet,
    val parameters: HttpRequestParameters,
) : ToJsonObject {

    protected val columns = mutableListOf<PagedColumn>()
    protected val defaultSort = mutableListOf<Pair<Expression<*>, SortOrder>>()
    protected val searchColumns = mutableListOf<Column<out String?>>()
    protected val searchSubQueries = mutableListOf<(String) -> Op<Boolean>>()

    protected fun returnAllColumns(table: ColumnSet = this.table) {
        returnColumns(table.columns)
    }

    protected fun returnColumns(columns: List<Column<*>>) {
        for (column in columns) {
            returnColumn(column)
        }
    }

    protected fun returnColumns(column: Column<*>, vararg more: Column<*>) {
        returnColumns(listOf(column) + more.toList())
    }

    protected fun returnColumns(nameColumn: Pair<String, Column<*>>, vararg more: Pair<String, Column<*>>) {
        for ((name, column) in (listOf(nameColumn) + more)) {
            returnColumn(name, sortExpression = column) { it[column] }
        }
    }

    protected fun returnColumn(column: Column<*>) {
        // The column itself is the sort expression, so `sort=<jsonName>:asc`
        // requests resolve against every returned table column.
        columns.add(PagedColumn(column.name.toJsonPropertyName(), sortExpression = column) { it[column] })
    }

    protected fun returnColumn(
        name: String,
        column: Expression<*>
    ) {
        columns.add(PagedColumn(name, sortExpression = column) { it[column] })
    }

    protected fun returnColumn(
        name: String,
        sortExpression: Expression<*>? = null,
        serialize: (ResultRow) -> Any?,
    ) {
        columns.add(PagedColumn(name, sortExpression, serialize))
    }

    protected fun setDefaultSort(column: Expression<*>, vararg more: Expression<*>) {
        setDefaultSort(column to SortOrder.ASC, *(more.map { it to SortOrder.ASC }.toTypedArray()))
    }

    protected fun setDefaultSort(order: Pair<Expression<*>, SortOrder>, vararg more: Pair<Expression<*>, SortOrder>) {
        defaultSort.add(order)
        defaultSort.addAll(more.toList())
    }

    @Suppress("UNCHECKED_CAST")
    protected fun addSearchOnAllStringColumns(table: ColumnSet = this.table) {
        for (column in table.columns) {
            val type = column.columnType
            if (type is StringColumnType) {
                searchColumns.add(column as Column<out String>)
            }
        }
    }

    protected fun addSearchedColumns(column: Column<out String?>, vararg more: Column<out String?>) {
        searchColumns.add(column)
        searchColumns.addAll(more)
    }

    protected fun addSearchedColumns(name: String, subQuery: (String) -> Op<Boolean>) {
        searchSubQueries.add(subQuery)
    }

    open fun basePredicate(): Op<Boolean>? {
        return null
    }

    open fun filters(parameters: HttpRequestParameters): List<Op<Boolean>> {
        return emptyList()
    }

    open fun source(): ColumnSet {
        return table
    }

    override fun toJsonObject(): JsonObject {
        return execute()
    }

    fun execute(): JsonObject {
        val request = PageRequest.from(parameters)
        val searchPredicate = request.search?.let { buildSearch(it) }
        val predicates = listOfNotNull(basePredicate()) + filters(parameters) + listOfNotNull(searchPredicate)
        val where = predicates.reduceOrNull { acc, op -> acc and op } ?: Op.TRUE
        val order = resolveSort(request.sort)
        val total = source().selectAll().where(where).count().toInt()
        val offset = (request.page - 1) * request.size
        val rows = source().selectAll().where(where)
            .orderBy(*(order.toTypedArray()))
            .limit(request.size).offset(offset.toLong())
            .toList()
        return json {
            "total" set total
            "page" set request.page
            "size" set request.size
            "data" set jsonArray {
                rows.forEach { row ->
                    addObject {
                        columns.forEach { col ->
                            col.name set JsonValue.toJsonValue(col.serialize(row))
                        }
                    }
                }
            }
        }
    }

    private fun buildSearch(term: String): Op<Boolean> {
        @Suppress("UNCHECKED_CAST")
        val columnPredicates = searchColumns.map { (it as Column<String?>) ilike term }
        val subQueryPredicates = searchSubQueries.map { it(term) }
        val all = columnPredicates + subQueryPredicates
        if (all.isEmpty()) return Op.TRUE
        return all.reduce { acc, op -> acc or op }
    }

    private fun resolveSort(fields: List<PageRequest.SortField>): List<Pair<Expression<*>, SortOrder>> {
        val sortableByName = columns.filter { it.sortExpression != null }.associateBy { it.name }
        val resolved = fields.mapNotNull { field ->
            sortableByName[field.name]?.let { it.sortExpression!! to field.direction }
        }
        return if (resolved.isNotEmpty()) {
            resolved
        } else {
            defaultSort
        }
    }

    private fun String.toJsonPropertyName(): String {
        return split('_')
            .filter { it.isNotEmpty() }
            .mapIndexed { index, part ->
                if (index == 0) {
                    part
                } else {
                    part.replaceFirstChar { it.uppercase() }
                }
            }
            .joinToString("")
    }
}
