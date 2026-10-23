package org.tekfive.keep.db

import org.jetbrains.exposed.v1.core.ForeignKeyConstraint
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.vendors.PostgreSQLDialectMetadata
import java.sql.DatabaseMetaData
import java.sql.ResultSet

/** Reads PostgreSQL foreign keys once per schema instead of once per table. */
internal class PostgresMetadata : PostgreSQLDialectMetadata() {
    override fun fillConstraintCacheForTables(tables: List<Table>) {
        if (tables.isEmpty()) return

        val connection = dbConnection()
        val identifiers = TransactionManager.current().db.identifierManager
        val currentSchema = connection.schema
        val allTables = SchemaUtils.sortTablesByReferences(tables)
        val tablesByName = allTables.associateBy {
            val schema = it.schemaName?.let(identifiers::inProperCase)?.removeSurrounding("\"")?.replace("\"\"", "\"")
                ?: currentSchema
            schema to it.nameInDatabaseCaseUnquoted()
        }
        val columns = allTables.associateWith { table -> table.columns.associateBy { it.nameUnquoted() } }
        val constraints = allTables.associateWith { linkedMapOf<String, ForeignKeyConstraint>() }
        val metadata = connection.metaData
        val catalog = connection.catalog

        for (schema in tablesByName.keys.map { it.first }.distinct()) {
            // pgjdbc accepts a null table to return every foreign key in this schema.
            metadata.getImportedKeys(catalog, schema, null).use { rows ->
                while (rows.next()) {
                    val fromTable = tablesByName[rows.getString("FKTABLE_SCHEM") to rows.getString("FKTABLE_NAME")] ?: continue
                    val targetTable = tablesByName[rows.getString("PKTABLE_SCHEM") to rows.getString("PKTABLE_NAME")] ?: continue
                    val from = columns.getValue(fromTable)[rows.getString("FKCOLUMN_NAME")] ?: continue
                    val target = columns.getValue(targetTable)[rows.getString("PKCOLUMN_NAME")] ?: continue
                    val name = rows.getString("FK_NAME")
                    val constraint = ForeignKeyConstraint(
                        target = target,
                        from = from,
                        onUpdate = rows.referenceOption("UPDATE_RULE"),
                        onDelete = rows.referenceOption("DELETE_RULE"),
                        name = name,
                    )
                    constraints.getValue(fromTable).merge(name, constraint, ForeignKeyConstraint::plus)
                }
            }
        }
        // Include empty tables so repeated metadata requests do not query them again.
        for ((table, found) in constraints) {
            columnConstraintsCache[table.nameInDatabaseCaseUnquoted()] = found.values.toList()
        }
    }

    private fun ResultSet.referenceOption(column: String): ReferenceOption? {
        val rule = getInt(column)
        if (wasNull()) return null
        return when (rule) {
            DatabaseMetaData.importedKeyCascade -> ReferenceOption.CASCADE
            DatabaseMetaData.importedKeyRestrict -> ReferenceOption.RESTRICT
            DatabaseMetaData.importedKeySetNull -> ReferenceOption.SET_NULL
            DatabaseMetaData.importedKeyNoAction -> ReferenceOption.NO_ACTION
            DatabaseMetaData.importedKeySetDefault -> ReferenceOption.SET_DEFAULT
            else -> error("Unknown JDBC foreign key action: $rule")
        }
    }
}
