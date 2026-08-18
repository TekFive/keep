package org.tekfive.keep.db

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.tekfive.keep.data.TestDatabase
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DbConnectionReachabilityIntegrationTest {
    @Test
    fun `isReachable returns true for a valid database connection`() {
        withDefaultDatabase(TestDatabase.connect()) {
            assertTrue(DbConnection.isReachable())
            assertTrue(DbConnection.isReachable(validationTimeoutSeconds = 1))
        }
    }

    @Test
    fun `isReachable returns false when opening a connection fails`() {
        val unreachable = Database.connect(
            getNewConnection = { throw SQLException("Database is unavailable") },
            databaseConfig = DatabaseConfig { explicitDialect = PostgreSQLDialect() },
        )

        withDefaultDatabase(unreachable) {
            assertFalse(DbConnection.isReachable())
        }
    }

    private fun withDefaultDatabase(database: Database, block: () -> Unit) {
        val previous = TransactionManager.defaultDatabase
        try {
            TransactionManager.defaultDatabase = database
            block()
        } finally {
            TransactionManager.closeAndUnregister(database)
            TransactionManager.defaultDatabase = previous
        }
    }
}
