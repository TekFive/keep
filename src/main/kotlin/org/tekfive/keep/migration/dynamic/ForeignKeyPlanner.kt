package org.tekfive.keep.migration.dynamic

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresForeignKeyConstraintDefinition
import java.sql.Connection

/** Foreign-key drops precede table changes; additions wait until every table and unique key exists. */
internal fun planForeignKeys(
    connection: Connection,
    schema: KeepSchema,
): Pair<List<DropConstraint>, List<PostgresMigrationStatement>> {
    val drops = mutableListOf<DropConstraint>()
    val adds = mutableListOf<PostgresMigrationStatement>()
    val transaction = TransactionManager.current()
    val declared = schema.declaredPostgresObjects.filterIsInstance<PostgresForeignKeyConstraintDefinition>()
    schema.tables.forEach { table ->
        val tableName = SqlCursor(transaction.identity(table)).name()
        val desired = declared.filter { it.table == table }.associate { definition ->
            definition.name to ConstraintDefinition.ForeignKey(
                name = definition.name,
                columns = definition.references.map { it.first.nameUnquoted() },
                referencedTable = QualifiedName(definition.referencedTable.nameInDatabaseCaseUnquoted(), schema.schemaName),
                referencedColumns = definition.references.map { it.second.nameUnquoted() },
                onDelete = ReferentialAction.valueOf(definition.onDelete.name),
                onUpdate = ReferentialAction.valueOf(definition.onUpdate.name),
                deferrable = definition.deferrable,
                initiallyDeferred = definition.initiallyDeferred,
            )
        }
        val exposedNames = table.foreignKeys.mapTo(mutableSetOf()) { it.fkName.removeSurrounding("\"") }
        require(desired.keys.none { it in exposedNames }) { "Foreign keys on ${table.tableName} cannot be declared in both Exposed and post-schema objects" }
        val existing = readForeignKeys(connection, tableName, desired.keys)
        existing.filter { (name, actual) ->
            name !in desired && name !in exposedNames && table.foreignKeys.none { exposed ->
                // Exposed matches foreign keys by their column mapping, even when PostgreSQL
                // supplied the constraint name (for example after a column rename).
                actual.definition.columns == exposed.from.map { it.nameUnquoted() } &&
                    actual.definition.referencedColumns == exposed.target.map { it.nameUnquoted() } &&
                    actual.definition.referencedTable == SqlCursor(transaction.identity(exposed.targetTable)).name().let {
                        if (it.schema == null) it.copy(schema = schema.schemaName) else it
                    }
            }
        }.forEach { (name, _) ->
            drops += DropConstraint(tableName, name)
        }
        desired.forEach { (name, definition) ->
            val actual = existing[name]
            if (actual == null) {
                adds += AddConstraint(tableName, definition)
            } else if (actual.definition != definition || actual.matchType != "s") {
                drops += DropConstraint(tableName, name)
                adds += AddConstraint(tableName, definition)
            } else if (!actual.validated) {
                adds += ValidateConstraint(tableName, name)
            }
        }
    }
    return drops to adds
}

private data class ExistingForeignKey(
    val definition: ConstraintDefinition.ForeignKey,
    val matchType: String,
    val validated: Boolean,
)

private fun readForeignKeys(connection: Connection, table: QualifiedName, declaredNames: Set<String>): Map<String, ExistingForeignKey> =
    connection.prepareStatement(
        """
        SELECT c.conname,
               ARRAY(SELECT a.attname FROM unnest(c.conkey) WITH ORDINALITY k(num, ord)
                     JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.num ORDER BY k.ord),
               n.nspname, r.relname,
               ARRAY(SELECT a.attname FROM unnest(c.confkey) WITH ORDINALITY k(num, ord)
                     JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.num ORDER BY k.ord),
               c.confdeltype, c.confupdtype, c.condeferrable, c.condeferred, c.confmatchtype, c.convalidated, c.contype
        FROM pg_constraint c
        LEFT JOIN pg_class r ON r.oid = c.confrelid
        LEFT JOIN pg_namespace n ON n.oid = r.relnamespace
        WHERE c.conrelid = to_regclass(?) AND c.conparentid = 0
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, table.toSql())
        statement.executeQuery().use { result ->
            buildMap {
                while (result.next()) {
                    val name = result.getString(1)
                    if (result.getString(12) != "f") {
                        require(name !in declaredNames) { "Constraint $name on ${table.toSql()} exists but is not a foreign key" }
                        continue
                    }
                    put(name, ExistingForeignKey(
                        ConstraintDefinition.ForeignKey(
                            name,
                            (result.getArray(2).array as Array<*>).map { it.toString() },
                            QualifiedName(result.getString(4), result.getString(3)),
                            (result.getArray(5).array as Array<*>).map { it.toString() },
                            action(result.getString(6)), action(result.getString(7)),
                            result.getBoolean(8), result.getBoolean(9),
                        ), result.getString(10), result.getBoolean(11),
                    ))
                }
            }
        }
    }

private fun action(code: String): ReferentialAction = when (code) {
    "a" -> ReferentialAction.NO_ACTION
    "r" -> ReferentialAction.RESTRICT
    "c" -> ReferentialAction.CASCADE
    "n" -> ReferentialAction.SET_NULL
    "d" -> ReferentialAction.SET_DEFAULT
    else -> error("Unknown PostgreSQL referential action: $code")
}
