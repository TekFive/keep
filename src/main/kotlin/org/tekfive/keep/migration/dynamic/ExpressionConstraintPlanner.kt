package org.tekfive.keep.migration.dynamic

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresExpressionConstraintDefinition
import java.sql.Connection
import java.util.UUID

internal fun planExpressionConstraints(connection: Connection, schema: KeepSchema): Pair<List<DropConstraint>, List<PostgresMigrationStatement>> {
    val drops = mutableListOf<DropConstraint>()
    val adds = mutableListOf<PostgresMigrationStatement>()
    val declarations = schema.declaredPostgresObjects.filterIsInstance<PostgresExpressionConstraintDefinition>()
    schema.tables.forEach { table ->
        val name = SqlCursor(TransactionManager.current().identity(table)).name()
        val desired = declarations.filter { it.table == table }.associateBy { it.name }
        val nativeNames = table.checkConstraints().mapTo(mutableSetOf()) { it.checkName }
        val actual = readExpressionConstraints(connection, name)
        actual.filter { (key, value) -> value.type in listOf("c", "x") && key !in desired && key !in nativeNames }.forEach { (key, _) ->
            drops += DropConstraint(name, key)
        }
        desired.forEach { (key, definition) ->
            require(key !in nativeNames) { "Check constraint $key is declared twice" }
            val existing = actual[key]
            if (existing == null) adds += AddConstraint(name, definition.definition)
            else {
                val type = if (definition.definition is ConstraintDefinition.Check) "c" else "x"
                require(existing.type == type) { "Constraint $key on ${name.toSql()} has an incompatible type" }
                val expected = canonicalConstraint(connection, definition)
                if (existing.sql != expected.sql) {
                    drops += DropConstraint(name, key)
                    adds += AddConstraint(name, definition.definition)
                } else if (!existing.valid) adds += ValidateConstraint(name, key)
            }
        }
    }
    return drops to adds
}

private data class ExistingExpressionConstraint(val type: String, val sql: String, val valid: Boolean)

private fun canonicalConstraint(connection: Connection, definition: PostgresExpressionConstraintDefinition): ExistingExpressionConstraint {
    val temporary = QualifiedName("keep_constraint_${UUID.randomUUID().toString().replace("-", "")}", "pg_temp")
    val savepoint = connection.setSavepoint()
    try {
        val columns = definition.table.columns.joinToString { "${quoteIdentifier(it.nameUnquoted())} ${it.columnType.sqlType()}" }
        connection.createStatement().use { statement ->
            statement.execute("CREATE TEMPORARY TABLE ${temporary.toSql()} ($columns)")
            statement.execute(AddConstraint(temporary, definition.definition).toSql())
        }
        return readExpressionConstraints(connection, temporary).getValue(definition.name)
    } finally {
        connection.rollback(savepoint)
        connection.releaseSavepoint(savepoint)
    }
}

private fun readExpressionConstraints(connection: Connection, table: QualifiedName): Map<String, ExistingExpressionConstraint> =
    connection.prepareStatement("SELECT conname, contype, pg_get_constraintdef(oid, true), convalidated FROM pg_constraint WHERE conrelid = to_regclass(?) AND conparentid = 0").use { statement ->
        statement.setString(1, table.toSql())
        statement.executeQuery().use { result ->
            buildMap {
                while (result.next()) put(result.getString(1), ExistingExpressionConstraint(result.getString(2),
                    result.getString(3).removeSuffix(" NOT VALID"), result.getBoolean(4)))
            }
        }
    }
