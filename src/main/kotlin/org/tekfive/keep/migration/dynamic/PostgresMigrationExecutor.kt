package org.tekfive.keep.migration.dynamic

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.db.dbConnection
import java.sql.Connection

/** Runs dynamic plans without recording a version in MigrationHistoryTable. */
object PostgresMigrationExecutor {
    fun execute(plan: PostgresMigrationPlan, database: Database): Int {
        val current = TransactionManager.currentOrNull()
        if (current != null) {
            require(current.db === database) { "The active transaction belongs to another database" }
            return execute(plan)
        }
        return transaction(database) {
            maxAttempts = 1 // Replaying partially executed DDL is not an executor retry strategy.
            execute(plan)
        }
    }

    fun execute(plan: PostgresMigrationPlan): Int {
        val transaction = TransactionManager.current()
        val connection = dbConnection()
        val sql = validate(plan, connection, autocommit = false)
        val savepoint = connection.setSavepoint()
        try {
            val count = run(sql, connection)
            connection.releaseSavepoint(savepoint)
            return count
        } catch (failure: Throwable) {
            try {
                connection.rollback(savepoint)
                connection.releaseSavepoint(savepoint)
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
                try { connection.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            }
            throw failure
        } finally {
            transaction.db.dialectMetadata.resetCaches()
        }
    }

    fun executeAutocommit(plan: PostgresMigrationPlan, database: Database): Int {
        check(TransactionManager.currentOrNull() == null) { "Autocommit execution cannot run inside an existing transaction" }
        val exposedConnection = database.connector()
        try {
            val connection = exposedConnection.connection as Connection
            // This is a new, executor-owned connection, so changing its mode cannot commit caller work.
            connection.autoCommit = true
            return run(validate(plan, connection, autocommit = true), connection)
        } finally {
            exposedConnection.close() // No Exposed transaction metadata is used on this dedicated JDBC connection.
        }
    }

    private fun validate(plan: PostgresMigrationPlan, connection: Connection, autocommit: Boolean): List<String> {
        require(connection.metaData.databaseProductName == "PostgreSQL") { "Dynamic migrations require PostgreSQL" }
        val version = connection.metaData.databaseMajorVersion
        return plan.statements.map { statement ->
            require(version >= statement.minimumPostgresVersion) { "${statement::class.simpleName} requires PostgreSQL ${statement.minimumPostgresVersion}+" }
            require(autocommit || statement.transactionRequirement == TransactionRequirement.TRANSACTIONAL) {
                "${statement::class.simpleName} requires executeAutocommit(database)"
            }
            statement.toSql()
        }
    }

    private fun run(sql: List<String>, connection: Connection): Int {
        sql.forEachIndexed { index, text ->
            try {
                connection.createStatement().use { it.execute(text) }
            } catch (failure: Exception) {
                throw PostgresMigrationExecutionException(index, text, failure)
            }
        }
        return sql.size
    }
}

/** In autocommit mode [statementIndex] earlier statements have committed; atomic execution rolls them back. */
class PostgresMigrationExecutionException(
    val statementIndex: Int,
    val sql: String,
    cause: Throwable,
) : RuntimeException("Dynamic migration statement ${statementIndex + 1} failed: $sql", cause)
