package org.tekfive.keep.job.db

import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

object PostgresTestSupport {

    val CREATE_JOBS_TABLE_DDL: String =
        PostgresTestSupport::class.java.getResource("/jobs.sql")!!.readText()

    fun truncateJobsTable(container: PostgreSQLContainer<*>) {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE TABLE ${JobRecordsTable.tableName} RESTART IDENTITY CASCADE") }
        }
    }

    fun initSchema(container: PostgreSQLContainer<*>) {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { it.execute(CREATE_JOBS_TABLE_DDL) }
        }
    }

    fun getConnection(container: PostgreSQLContainer<*>) =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
}
