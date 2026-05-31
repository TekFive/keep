package org.tekfive.keep.migration

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationHistoryTableTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction { SchemaUtils.create(MigrationHistoryTable) }
    }

    @AfterTest
    fun teardown() {
        runCatching { transaction { SchemaUtils.drop(MigrationHistoryTable) } }
    }

    @Test
    fun `insert and read a row`() {
        val now = System.currentTimeMillis()
        transaction {
            MigrationHistoryTable.insert {
                it[version] = 7L
                it[name] = "add_workflow_priority"
                it[appliedAt] = now
                it[appliedBy] = "host-1"
            }
        }
        transaction {
            val row = MigrationHistoryTable
                .selectAll()
                .where { MigrationHistoryTable.version eq 7L }
                .single()
            assertEquals("add_workflow_priority", row[MigrationHistoryTable.name])
            assertEquals("host-1", row[MigrationHistoryTable.appliedBy])
        }
    }
}
