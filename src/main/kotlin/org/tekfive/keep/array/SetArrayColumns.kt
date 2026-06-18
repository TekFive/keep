package org.tekfive.keep.array

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.array
import org.jetbrains.exposed.v1.core.transform

/**
 * Registers a PostgreSQL array column exposed as a [Set].
 *
 * Exposed's native PostgreSQL array mapping uses [List]. Keep stores the same
 * database representation while presenting a set-oriented Kotlin API.
 */
inline fun <reified E : Any> Table.setArray(name: String): Column<Set<E>> {
    return array<E>(name).asSetColumn()
}

/**
 * Registers a PostgreSQL array column exposed as a [Set] with an explicit element column type.
 */
fun <E : Any> Table.setArray(name: String, columnType: ColumnType<E>): Column<Set<E>> {
    return array(name, columnType).asSetColumn()
}

fun <E : Any> Column<List<E>>.asSetColumn(): Column<Set<E>> {
    return transform(
        wrap = { values -> values.toSet() },
        unwrap = { values -> values.toList() },
    )
}
