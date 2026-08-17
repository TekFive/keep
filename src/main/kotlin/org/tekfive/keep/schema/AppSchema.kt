package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.DataTableSchemaHooks
import org.tekfive.keep.db.db
import org.tekfive.keep.db.dbConnection
import kotlin.io.use

/**
 * Groups related tables into a named application schema. Provides lifecycle methods
 * for creating and dropping all tables in the correct order.
 *
 * ```
 * object MyAppSchema : AppSchema("my_app") {
 *     override val tables = listOf(UsersTable, OrdersTable, UserOrderJoin)
 * }
 *
 * transaction { MyAppSchema.create() }
 * ```
 */
abstract class AppSchema(
    schemaName: String = "public",
) : KeepSchema(schemaName) {

    open val extensions: List<String> = emptyList()

    open val sequences: List<String> = listOf(DataTable.DeaultSequenceName)

    override val sequenceNames: List<String>
        get() = sequences

    /** Creates database extensions and all tables. Must be called within a transaction. */
    open fun create() {

        for (sequence in sequenceNames) {
            TransactionManager.current().exec("CREATE SEQUENCE IF NOT EXISTS $sequence AS BIGINT MAXVALUE 9223372036854775807 START 1;")
        }
        for (ext in extensions) {
            TransactionManager.current().exec("CREATE EXTENSION IF NOT EXISTS \"$ext\"")
        }
        runDataTableSql { it.customTypes }
        SchemaUtils.create(*tables.toTypedArray())
        runDataTableSql { it.customIndices }
        runDataTableSql { it.postSchemaCreateSql }
    }

    /** Creates extensions and only the tables that do not already exist. Must be called within a transaction. */
    fun createIfNecessary(): Boolean {

        val tablesNames = SchemaUtils.listTables()
        if (tables.any { table -> tablesNames.any { it.equals(table.tableName, true) }}) {
            return false
        }

        create()
        return true
    }

    /** Drops all tables in reverse order using [SchemaUtils.drop]. Must be called within a transaction. */
    fun drop() {
        SchemaUtils.drop(*tables.reversed().toTypedArray())
    }

    fun getNextSequenceValue(sequenceName: String): Long {
        return db {
            val connection = dbConnection()
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT nextval('$sequenceName')").use { set ->
                    set.next()
                    set.getLong(1)
                }
            }
        }
    }

    private fun runDataTableSql(sqlProvider: (DataTableSchemaHooks) -> List<String>) {
        tables.filterIsInstance<DataTableSchemaHooks>().forEach { table ->
            sqlProvider(table).forEach { sql ->
                TransactionManager.current().exec(sql)
            }
        }
    }
}
