package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import java.util.Collections

/**
 * Declares historical SQL names for this column. Names are alternatives, not an ordered rename
 * chain. Supply exact database names without SQL quoting. Fresh installations use only [Column.name].
 * Metadata survives nullable/transform copies and is isolated by table instance.
 */
fun <T> Column<T>.renamedFrom(vararg names: String): Column<T> = apply {
    require(names.isNotEmpty()) { "renamedFrom requires at least one historical column name" }
    names.forEach { previous ->
        require(previous.isNotBlank() && '\u0000' !in previous) { "Historical column names must not be blank or contain NUL" }
        require(previous.toByteArray(Charsets.UTF_8).size <= 63) { "Historical column names must not exceed 63 UTF-8 bytes" }
        require(previous != name.trim('"')) { "Column '$name' cannot declare its current name as a historical name" }
    }
    ColumnRenames.bind(this, names.toList())
}

/** Read-only historical SQL names declared with [renamedFrom], in declaration order. */
val Column<*>.previousNames: List<String>
    get() = ColumnRenames.names(this)

private object ColumnRenames {
    private val plainTables = WeakIdentityMap<Table, MutableMap<String, List<String>>>()

    @Synchronized
    fun bind(column: Column<*>, names: List<String>) {
        val mappings = when (val table = column.table) {
            is TypedDataTuple<*, *> -> table.columnPreviousNames
            else -> plainTables[table] ?: linkedMapOf<String, List<String>>().also { plainTables[table] = it }
        }
        mappings[column.name] = Collections.unmodifiableList((mappings[column.name].orEmpty() + names).distinct())
    }

    @Synchronized
    fun names(column: Column<*>): List<String> = when (val table = column.table) {
        is TypedDataTuple<*, *> -> table.columnPreviousNames[column.name].orEmpty()
        else -> plainTables[table]?.get(column.name).orEmpty()
    }
}
