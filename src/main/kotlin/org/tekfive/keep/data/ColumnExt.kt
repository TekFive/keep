package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.BinaryColumnType
import org.jetbrains.exposed.v1.core.CharacterColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table.Dual.index
import org.jetbrains.exposed.v1.core.Table.Dual.references
import org.jetbrains.exposed.v1.core.Table.Dual.uniqueIndex
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.tekfive.keep.text.CitextColumnType
import org.tekfive.keep.schema.postgresObjects

/** Returns the max length of this column's type, or [Int.MAX_VALUE] if the type has no length. */
val Column<*>.maxLength: Int
    get() = when (val type = columnType) {
        is VarCharColumnType -> type.colLength
        is CitextColumnType -> type.colLength
        is BinaryColumnType -> type.length
        is CharacterColumnType -> 1
        else -> Int.MAX_VALUE
    }


fun <T> Column<T>.indexWithStandardName(): Column<T> = index("${table.tableName}_${name}_ix")

fun <T> Column<T>.uniqueIndexWithStandardName(): Column<T> = uniqueIndex("${table.tableName}_${name}_uq")

/**
 * Makes this KEEP table column unique, treating nulls as equal (at most one null).
 *
 * Registers a PostgreSQL 15+ `UNIQUE NULLS NOT DISTINCT` constraint and its backing index.
 * Created by KEEP's schema creation and migration APIs; returns this column for further chaining.
 */
fun <T> Column<T>.uniqueNonNullUniqueIndex(
    customIndexName: String = "${table.tableName.substringAfterLast('.').trim('"')}_${name}_uq",
): Column<T> {
    val keepTable = table as? TypedDataTuple<*, *>
        ?: throw IllegalArgumentException("uniqueNonNullUniqueIndex requires a KEEP table")
    keepTable.columnPostgresObjects += table.postgresObjects {
        uniqueConstraint(customIndexName, this@uniqueNonNullUniqueIndex, nullsNotDistinct = true)
    }
    return this
}

fun <T : Comparable<T>, S : T, C : Column<S>> C.referencesWithStandardNameAndIndex(reference: Column<T>, onDelete: ReferenceOption = ReferenceOption.CASCADE, onUpdate: ReferenceOption? = null): Column<S> {
    return references(reference, onDelete, onUpdate, fkName = "${table.tableName}_${name}_fk").indexWithStandardName()
}

/**
 * Returns an [Expression] that sorts by the [DataEnum.displayName] of each enum value
 * instead of by the stored integer id.
 *
 * Generates a SQL `CASE` expression mapping each id to its displayText:
 * ```sql
 * CASE column WHEN 1 THEN 'Active' WHEN 2 THEN 'Inactive' ... END
 * ```
 *
 * Usage: `query.orderBy(MyTable.status.enumOrder() to SortOrder.ASC)`
 */
fun <E> Column<E>.enumOrder(): Expression<String> where E : Enum<E>, E : DataEnum {
    val enumColumnType = columnType as DataEnumColumnType<E>
    val column = this
    return object : Expression<String>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder {
                append("CASE ")
                append(column)
                for (value in enumColumnType.dataEnumValues) {
                    append(" WHEN ${value.id} THEN '${value.displayName.replace("'", "''")}'")
                }
                append(" END")
            }
        }
    }
}
