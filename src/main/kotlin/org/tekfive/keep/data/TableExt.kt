package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.greater
import org.tekfive.keep.text.citext

/** Creates an indexed long column that references [target]'s `id` as a named foreign key. */
fun Table.fkey(name: String, target: DataTable<*>, onDelete: ReferenceOption = ReferenceOption.CASCADE): Column<Long> {
    return long(name).references(target.id, fkName = "${tableName}_${name}_fk", onDelete = onDelete).indexWithStandardName()
}

fun Table.timestamp(name: String): Column<Long> {
    return long(name).check("${tableName}_${name}_positive") { it greater 0L }
}
fun Table.createdAt() = timestamp("created_at")

fun Table.updatedAt() = timestamp("updated_at")

fun Table.addedAt() = timestamp("added_at")

fun Table.active(name: String = "active"): Column<Boolean> = bool(name)

/** Creates a name column. Case-insensitive when [caseInsensitive] is true (uses CITEXT), otherwise VARCHAR. */
fun Table.name(name: String = "name", caseInsensitive: Boolean = true, unique: Boolean = false): Column<String> {
    val col = if (caseInsensitive) citext(name, 255) else varchar(name, 255)
    return if (unique) col.uniqueIndex() else col
}

/** Creates an optional text column for descriptions, with a full-text search index. */
fun Table.description(name: String = "description"): Column<String?> =
    text(name).nullable()

/** Creates a case-insensitive email address column (CITEXT, max 320 chars) with a unique index. */
fun Table.emailAddress(name: String = "email_address", unique: Boolean = true): Column<String> {
    val address = citext(name, 320)
    if (unique) {
        address.uniqueIndex("${tableName}_${name}_uq")
    }
    return address
}

/** Creates a VARCHAR column for phone numbers. */
fun Table.phoneNumber(name: String = "phone_number", unique: Boolean = true): Column<String> {
    val number = varchar(name, 30)
    if (unique) {
        number.uniqueIndex("${tableName}_${name}_uq")
    }
    return number
}

fun Table.uniqueConstraint(column1: Column<*>, column2: Column<*>, vararg additionalColumns: Column<*>) {
    val columns = listOf(column1) + listOf(column2) + additionalColumns
    uniqueIndex("${tableName}_${columns.joinToString("_") { it.name }}", *columns.toTypedArray())
}