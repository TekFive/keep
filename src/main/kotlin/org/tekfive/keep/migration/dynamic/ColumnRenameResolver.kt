package org.tekfive.keep.migration.dynamic

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.tekfive.keep.data.previousNames
import java.sql.Connection

/** Validates all declarations and resolves historical names without changing the database. */
internal fun resolveColumnRenames(connection: Connection, tables: List<Table>): List<RenameColumn> {
    val transaction = TransactionManager.current()
    return buildList {
        for (table in tables) {
            val declared = table.columns.associateWith { column ->
                val rendered = transaction.identity(column)
                if (rendered.startsWith('"')) rendered.substring(1, rendered.length - 1).replace("\"\"", "\"")
                else rendered.lowercase(java.util.Locale.ROOT)
            }
            val claimedNames = mutableMapOf<String, String>()
            for ((column, currentName) in declared) {
                for (previous in column.previousNames) {
                    require(previous !in declared.values) {
                        "Historical column '$previous' for ${table.tableName}.$currentName is also a declared current column"
                    }
                    val claimant = claimedNames.putIfAbsent(previous, currentName)
                    require(claimant == null || claimant == currentName) {
                        "Historical column '${table.tableName}.$previous' is claimed by both '$claimant' and '$currentName'"
                    }
                }
            }
            if (claimedNames.isEmpty()) continue

            val tableName = transaction.identity(table)
            val existing = connection.prepareStatement(
                "SELECT attname FROM pg_attribute WHERE attrelid = to_regclass(?) AND attnum > 0 AND NOT attisdropped",
            ).use { statement ->
                statement.setString(1, tableName)
                statement.executeQuery().use { result ->
                    buildSet { while (result.next()) add(result.getString(1)) }
                }
            }
            for ((column, currentName) in declared) {
                val historical = column.previousNames.filter { it in existing }
                require(historical.size <= 1 && (historical.isEmpty() || currentName !in existing)) {
                    "Ambiguous rename for ${table.tableName}.$currentName: existing columns " +
                        (historical + listOfNotNull(currentName.takeIf { it in existing })).joinToString() +
                        ". Resolve the columns explicitly before generating a migration."
                }
                val previous = historical.singleOrNull() ?: continue
                add(RenameColumn(SqlCursor(tableName).name(), previous, currentName))
            }
        }
    }
}
