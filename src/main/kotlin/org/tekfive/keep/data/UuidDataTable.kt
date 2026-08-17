package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.tekfive.keep.db.db
import org.tekfive.keep.utils.ColumnValueMapper
import org.tekfive.keep.utils.FilteredUpdateStatement
import java.util.UUID
import kotlin.reflect.KProperty1
import org.jetbrains.exposed.v1.jdbc.update as jdbcUpdate

/** Writable KEEP table whose primary key is a client-generated UUIDv7. */
abstract class UuidDataTable<D : UuidData>(
    name: String,
) : UuidDataTuple<D>(name, managedColumns = setOf("id")), DataTableSchemaHooks {

    override val id: Column<UUID> = javaUUID("id").clientDefault(::uuidV7)

    override val primaryKey = PrimaryKey(id)

    @Suppress("UNCHECKED_CAST")
    private val varPropertyList: List<KProperty1<Any, *>> by lazy {
        varProperties.values.map { it as KProperty1<Any, *> }
    }

    open fun mapColumns(data: D, statement: ColumnValueMapper, insert: Boolean) {
        if (insert) {
            valProperties.forEach { (name, property) -> mapProperty(name, property, data, statement) }
        }
        varProperties.forEach { (name, property) -> mapProperty(name, property, data, statement) }
    }

    private fun mapProperty(
        name: String,
        property: KProperty1<*, *>,
        data: D,
        statement: ColumnValueMapper,
    ) {
        val column = resolveColumn(name)
        if (column != null) {
            @Suppress("UNCHECKED_CAST")
            val value = (property as KProperty1<D, *>).get(data)
            val mapped = if (value is Collection<*> && value.isEmpty() && column.columnType.nullable) null else value
            @Suppress("UNCHECKED_CAST")
            statement[column as Column<Any?>] = mapped
        } else {
            @Suppress("UNCHECKED_CAST")
            val group = resolveColumnGroup(name) as? ColumnGroup<Any?>
            @Suppress("UNCHECKED_CAST")
            val value = (property as KProperty1<D, *>).get(data)
            group?.mapColumns(value, statement)
        }
    }

    override fun map(row: ResultRow): D = super.map(row).also {
        it.linkToDB(row[id])
        it.snapshot(varPropertyList)
    }

    open fun create(data: D): D {
        check(data.notLinkedToDb) { "Cannot create: data already has id ${data.idOrNull}" }
        return db {
            val result = insert { statement ->
                mapColumns(data, FilteredUpdateStatement(statement), insert = true)
            }
            data.linkToDB(result[id])
            data.snapshot(varPropertyList)
            data
        }
    }

    open fun create(data: D, id: UUID): D {
        check(data.notLinkedToDb) { "Cannot create: data already has id ${data.idOrNull}" }
        return db {
            insert { statement ->
                statement[this@UuidDataTable.id] = id
                mapColumns(data, FilteredUpdateStatement(statement), insert = true)
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
            resolveColumn(name)?.let(::listOf) ?: resolveColumnGroup(name)?.columns.orEmpty()
        }.toSet()
        return updateColumns(data, dirtyColumns)
    }

    open fun update(data: D, column: Column<*>, vararg additionalColumns: Column<*>): D {
        check(data.linkedToDb) { "Cannot update: data has no id" }
        return updateColumns(data, linkedSetOf(column).also { it.addAll(additionalColumns) })
    }

    private fun updateColumns(data: D, requestedColumns: Set<Column<*>>): D {
        require(requestedColumns.isNotEmpty()) { "Cannot update: at least one column must be specified" }
        validateUpdateColumns(requestedColumns)
        val columnsToUpdate = requestedColumns.toMutableSet()

        if (data is TrackUpdatedAt && columnsToUpdate.none { it.name == "updated_at" }) {
            val updatedAtColumn = columns.firstOrNull { it.name == "updated_at" }
            check(updatedAtColumn != null) {
                "Data implements RecordUpdatedAt but $tableName has no updated_at column."
            }
            data.updatedAt = System.currentTimeMillis()
            columnsToUpdate += updatedAtColumn
        }

        val snapshotProperties = snapshotPropertiesFor(columnsToUpdate)
        return db {
            val dataId = data.id
            jdbcUpdate({ id eq dataId }) { statement ->
                val mapper = FilteredUpdateStatement(statement, columnsToUpdate)
                mapColumns(data, mapper, insert = true)
                val unmappedColumns = columnsToUpdate - mapper.updatedColumns.toSet()
                check(unmappedColumns.isEmpty()) {
                    "Cannot update columns not mapped from ${dataClass.simpleName}: " +
                        unmappedColumns.joinToString { it.name }
                }
            }
            data.snapshot(varPropertyList, snapshotProperties)
            data
        }
    }

    private fun validateUpdateColumns(columnsToUpdate: Set<Column<*>>) {
        val unknownColumns = columnsToUpdate - columns.toSet()
        check(unknownColumns.isEmpty()) {
            "Cannot update columns not owned by $tableName: ${unknownColumns.joinToString { it.name }}"
        }
        check(id !in columnsToUpdate) { "Cannot update managed id column" }
    }

    private fun snapshotPropertiesFor(columnsToUpdate: Set<Column<*>>): Set<String> {
        val properties = mutableSetOf<String>()
        varProperties.forEach { (name, _) ->
            resolveColumn(name)?.let { column ->
                if (column in columnsToUpdate) properties += name
            }
            resolveColumnGroup(name)?.let { group ->
                if (group.columns.all { it in columnsToUpdate }) properties += name
            }
        }
        return properties
    }

    open fun save(data: D): D = if (data.linkedToDb) update(data) else create(data)

    open fun delete(data: D) {
        check(data.idOrNull != null) { "Cannot delete: data has no id" }
        db {
            val dataId = data.id
            deleteWhere { id eq dataId }
            data.unlinkFromDB()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> deleteByColumnValue(value: T, column: Column<T>): Int =
        deleteWhere { (column as Column<Any?>) eq (value as Any?) }

    override val postSchemaCreateSql: List<String> = emptyList()

    override val customTypes: List<String> = emptyList()

    override val customIndices: List<String> = emptyList()
}
