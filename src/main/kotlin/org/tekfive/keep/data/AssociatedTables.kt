package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Table
import java.util.Collections
import java.util.IdentityHashMap

/** Additional tables that must be included in the application's schema. */
interface HasAssociatedTables {
    val associatedTables: List<Table>
}

/** Expands associated tables once, in declaration order. Rejects conflicting objects with the same SQL name. */
fun withAssociatedTables(tables: Iterable<Table>): List<Table> {
    val visited = Collections.newSetFromMap(IdentityHashMap<Table, Boolean>())
    val names = mutableMapOf<String, Table>()
    val result = mutableListOf<Table>()
    fun visit(table: Table) {
        if (!visited.add(table)) return
        val previous = names.putIfAbsent(table.tableName, table)
        require(previous == null) { "Different table objects declare ${table.tableName}." }
        result.add(table)
        if (table is HasAssociatedTables) table.associatedTables.forEach(::visit)
    }
    tables.forEach(::visit)
    return result
}
