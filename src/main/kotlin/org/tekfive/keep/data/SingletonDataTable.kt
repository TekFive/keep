package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.tekfive.keep.db.db
import org.tekfive.keep.utils.FilteredUpdateStatement

/**
 * A normal [DataTable] with at most one row, suitable for application settings or shared state.
 * A unique index on a constant enforces the limit for all writers, including concurrent inserts
 * and direct SQL. The table may be empty; deleting its row allows a new row to be created.
 *
 * Uses the usual Long identity, property mapping, change tracking, and schema generators.
 */
abstract class SingletonDataTable<D : Data>(
    name: String,
    idSequenceName: String = DeaultSequenceName,
) : DataTable<D>(name, idSequenceName) {

    init {
        // Declare a native index so SchemaUtils and subclasses' postgresObjects both retain it.
        uniqueIndex(
            customIndexName = "${tableName.substringAfterLast('.').removeSurrounding("\"")}_singleton_uq",
            functions = listOf(SingletonIndexKey),
        )
    }

    /** Returns the sole row, or null if the table is empty. Honors [createQuery]. */
    fun find(): D? = db { createQuery().singleOrNull()?.let(::map) }

    /** Returns the sole row, throwing [NoSuchElementException] if none exists. */
    fun get(): D = find() ?: throw NoSuchElementException("Singleton table $tableName is empty.")

    /**
     * Returns the existing row or initializes it. Concurrent initializers return the winning row;
     * the factory can run more than once across callers, so it should only construct data.
     * Returns a freshly mapped persisted object. Requires the normal READ COMMITTED isolation.
     */
    fun getOrCreate(factory: () -> D): D = db {
        find() ?: run {
            val data = factory()
            check(data.notLinkedToDb) { "Cannot initialize: data already has id ${data.idOrNull}" }
            insertIgnore { statement -> mapColumns(data, FilteredUpdateStatement(statement), insert = true) }
            get()
        }
    }
}

private object SingletonIndexKey : ExpressionWithColumnType<Boolean>() {
    override val columnType = BooleanColumnType()
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("(TRUE)")
    }
}
