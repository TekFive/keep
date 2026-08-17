package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.tekfive.keep.db.db
import org.jetbrains.exposed.v1.jdbc.update as jdbcUpdate
import org.tekfive.keep.utils.ColumnValueMapper
import org.tekfive.keep.utils.FilteredUpdateStatement
import kotlin.reflect.KProperty1

/**
 * Writable table class that extends [DataTuple] with an auto-increment `id` column
 * and CRUD operations (create, update, delete).
 *
 * Convention:
 * - `val` properties (immutable) are written only on insert.
 * - `var` properties (mutable) are written on every save (insert and update).
 * - The `id` column is managed by DataTable and is not part of the Data property mapping.
 */
abstract class DataTable<D : Data>(
    name: String,
    val idSequenceName: String = DeaultSequenceName,
) : DataTuple<D>(name, managedColumns = setOf("id")), DataTableSchemaHooks {

    override val id: Column<Long> = long("id").autoIncrement(idSequenceName)

    override val primaryKey = PrimaryKey(id)

    /** Ordered list of var properties cast for snapshot use. */
    @Suppress("UNCHECKED_CAST")
    private val varPropertyList: List<KProperty1<Any, *>> by lazy {
        varProperties.values.map { it as KProperty1<Any, *> }
    }

    /**
     * Maps data object properties to statement columns.
     * - On insert: writes `val` properties (immutable, set once) and `var` properties.
     * - On update: writes only `var` properties (mutable).
     *
     * Subclasses can override to add custom mapping logic (e.g. computed columns) and call
     * `super.mapColumns(data, statement, insert)` for the standard property mapping.
     */
    open fun mapColumns(data: D, statement: ColumnValueMapper, insert: Boolean) {
        if (insert) {
            for ((name, prop) in valProperties) {
                mapProperty(name, prop, data, statement)
            }
        }

        for ((name, prop) in varProperties) {
            mapProperty(name, prop, data, statement)
        }
    }

    private fun mapProperty(name: String, prop: KProperty1<*, *>, data: D, statement: ColumnValueMapper) {
        val column = resolveColumn(name)
        if (column != null) {
            @Suppress("UNCHECKED_CAST")
            val value = (prop as KProperty1<D, *>).get(data)
            val mapped = if (value is Collection<*> && value.isEmpty() && column.columnType.nullable) null else value
            @Suppress("UNCHECKED_CAST")
            statement[column as Column<Any?>] = mapped
        } else {
            @Suppress("UNCHECKED_CAST")
            val group = resolveColumnGroup(name) as? ColumnGroup<Any?>
            @Suppress("UNCHECKED_CAST")
            val value = (prop as KProperty1<D, *>).get(data)
            group?.mapColumns(value, statement)
        }
    }

    /**
     * Constructs a [Data] instance from a [ResultRow], links it to the database via its id,
     * and snapshots var properties for change tracking.
     */
    override fun map(row: ResultRow): D {
        return super.map(row).also {
            it.linkToDB(row[id])
            it.snapshot(varPropertyList)
        }
    }

    /** Inserts [data] as a new row and links it to its generated database id. */
    open fun create(data: D): D {
        check(data.notLinkedToDb) { "Cannot create: data already has id ${data.idOrNull}" }
        return db {
            val result = insert { stmt ->
                val mapper = FilteredUpdateStatement(stmt)
                mapColumns(data, mapper, insert = true)
            }
            data.linkToDB(result[id])
            data.snapshot(varPropertyList)
            data
        }
    }

    /** Inserts [data] using a caller-allocated [id] and links it to that database id. */
    open fun create(data: D, id: Long): D {
        check(data.notLinkedToDb) { "Cannot create: data already has id ${data.idOrNull}" }
        return db {
            insert { stmt ->
                stmt[this@DataTable.id] = id
                val mapper = FilteredUpdateStatement(stmt)
                mapColumns(data, mapper, insert = true)
            }
            data.linkToDB(id)
            data.snapshot(varPropertyList)
            data
        }
    }

    open fun update(data: D): D {
        check(data.linkedToDb) { "Cannot update: data has no id" }
        val dirty = data.dirtyProperties
        if (dirty.isEmpty()) return data

        val dirtyColumns = dirty.flatMap { name ->
            val column = resolveColumn(name)
            if (column != null) listOf(column)
            else resolveColumnGroup(name)?.columns ?: emptyList()
        }.toSet()

        return updateColumns(data, dirtyColumns)
    }

    /** Updates only [column] and [additionalColumns] for the existing row represented by [data]. */
    open fun update(data: D, column: Column<*>, vararg additionalColumns: Column<*>): D {
        check(data.linkedToDb) { "Cannot update: data has no id" }
        val columnsToUpdate = linkedSetOf(column).also { it.addAll(additionalColumns) }
        return updateColumns(data, columnsToUpdate)
    }

    private fun updateColumns(data: D, columnsToUpdate: Set<Column<*>>): D {
        require(columnsToUpdate.isNotEmpty()) { "Cannot update: at least one column must be specified" }
        validateUpdateColumns(columnsToUpdate)

        val columnsToUpdate = columnsToUpdate.toMutableSet()

        if (data is TrackUpdatedAt) {
            if (columnsToUpdate.none { it.name == "updated_at" }) {
                val updatedAtColumn = columns.firstOrNull { it.name == "updated_at" }
                check(updatedAtColumn != null) { "Data implements RecordUpdatedAt but $tableName has no updated_at column." }

                data.updatedAt = System.currentTimeMillis()
                columnsToUpdate.add(updatedAtColumn)
            }
        }

        val snapshotProperties = snapshotPropertiesFor(columnsToUpdate)
        return db {
            val idCol = id
            val dataId = data.id
            jdbcUpdate({ idCol eq dataId }) { stmt ->
                val mapper = FilteredUpdateStatement(stmt, columnsToUpdate)
                mapColumns(data, mapper, insert = true)
                val mappedColumns = mapper.updatedColumns.toSet()
                val unmappedColumns = columnsToUpdate - mappedColumns
                check(unmappedColumns.isEmpty()) {
                    "Cannot update columns not mapped from ${dataClass.simpleName}: ${unmappedColumns.joinToString { it.name }}"
                }
            }

            data.snapshot(varPropertyList, snapshotProperties)
            data
        }
    }

    private fun validateUpdateColumns(columnsToUpdate: Set<Column<*>>) {
        val tableColumns = columns.toSet()
        val unknownColumns = columnsToUpdate - tableColumns
        check(unknownColumns.isEmpty()) {
            "Cannot update columns not owned by $tableName: ${unknownColumns.joinToString { it.name }}"
        }
        check(id !in columnsToUpdate) { "Cannot update managed id column" }
    }

    private fun snapshotPropertiesFor(columnsToUpdate: Set<Column<*>>): Set<String> {
        val properties = mutableSetOf<String>()
        for ((name, _) in varProperties) {
            resolveColumn(name)?.let { column ->
                if (column in columnsToUpdate) properties.add(name)
            }

            resolveColumnGroup(name)?.let { group ->
                if (group.columns.all { it in columnsToUpdate }) {
                    properties.add(name)
                }
            }
        }
        return properties
    }

    /** Creates or updates [data] depending on whether it is linked to the database. */
    open fun save(data: D): D {
        return if (data.linkedToDb) update(data) else create(data)
    }

    /** Deletes the row for [data] and unlinks it from the database. */
    open fun delete(data: D) {
        check(data.idOrNull != null) { "Cannot delete: data has no id" }
        db {
            val dataId = data.id
            deleteWhere { id eq dataId }
            data.unlinkFromDB()
        }
    }

    /** Deletes all rows where [column] equals [value]. Returns the number of rows deleted. */
    @Suppress("UNCHECKED_CAST")
    fun <T> deleteByColumnValue(value: T, column: Column<T>): Int {
        return deleteWhere { (column as Column<Any?>) eq (value as Any?) }
    }

    override val postSchemaCreateSql: List<String> = emptyList()

    override val customTypes: List<String> = emptyList()

    override val customIndices: List<String> = emptyList()

    companion object {
        const val DeaultSequenceName = "GlobalId"
    }
}
