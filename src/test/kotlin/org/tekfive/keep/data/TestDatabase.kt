package org.tekfive.keep.data

import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Singleton PostgreSQL container shared across all DB tests. Started once on first access
 * and reused for the entire test run (Testcontainers Singleton pattern).
 */
object TestDatabase {
    private val container = PostgreSQLContainer("postgres:17-alpine").apply {
        withDatabaseName("keep_test")
        withUsername("test")
        withPassword("test")
        start()
    }

    fun connect(): Database = Database.connect(
        url = container.jdbcUrl,
        driver = "org.postgresql.Driver",
        user = container.username,
        password = container.password,
    )
}
