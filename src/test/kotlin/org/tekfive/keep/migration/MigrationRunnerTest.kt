package org.tekfive.keep.migration

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.db.db
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private object Tbl1 : Table("mr_test_t1") {
    val id = long("id")
    override val primaryKey = PrimaryKey(id)
}
private object Tbl2 : Table("mr_test_t2") {
    val id = long("id")
    override val primaryKey = PrimaryKey(id)
}

private class TableCreate(
    override val version: Long,
    override val name: String,
    private val table: Table,
) : Migration {
    var ran = false
        private set
    override fun apply(tx: JdbcTransaction) {
        SchemaUtils.create(table)
        ran = true
    }
}

private class AlwaysFails(
    override val version: Long,
    override val name: String,
) : Migration {
    override fun apply(tx: JdbcTransaction) {
        SchemaUtils.create(Tbl2)
        error("boom")
    }
}

class MigrationRunnerTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
    }

    @AfterTest
    fun teardown() {
        runCatching { transaction { SchemaUtils.drop(MigrationHistoryTable, Tbl1, Tbl2) } }
    }

    @Test
    fun `empty migration list is rejected`() {
        val ex = assertFailsWith<MigrationConfigurationException> {
            MigrationRunner.run(emptyList())
        }
        assertTrue(ex.message!!.contains("No migrations"))
    }

    @Test
    fun `duplicate versions are rejected`() {
        val ex = assertFailsWith<MigrationConfigurationException> {
            MigrationRunner.run(listOf(
                TableCreate(1L, "a", Tbl1),
                TableCreate(1L, "b", Tbl2),
            ))
        }
        assertTrue(ex.message!!.contains("Duplicate"))
    }

    @Test
    fun `non-positive version is rejected`() {
        val ex = assertFailsWith<MigrationConfigurationException> {
            MigrationRunner.run(listOf(TableCreate(0L, "zero", Tbl1)))
        }
        assertTrue(ex.message!!.contains("> 0"))
    }

    @Test
    fun `fresh database applies all migrations in order`() {
        val m1 = TableCreate(1L, "create_t1", Tbl1)
        val m2 = TableCreate(2L, "create_t2", Tbl2)

        MigrationRunner.run(listOf(m2, m1))  // intentionally out of order

        assertTrue(m1.ran)
        assertTrue(m2.ran)
        db {
            val rows = MigrationHistoryTable.selectAll().orderBy(MigrationHistoryTable.version).toList()
            assertEquals(listOf(1L, 2L), rows.map { it[MigrationHistoryTable.version] })
            assertEquals("create_t1", rows[0][MigrationHistoryTable.name])
            assertEquals("create_t2", rows[1][MigrationHistoryTable.name])
            assertNotNull(rows[0][MigrationHistoryTable.appliedAt])
            assertNotNull(rows[0][MigrationHistoryTable.appliedBy])
        }
    }

    @Test
    fun `already-applied migrations are skipped`() {
        val m1a = TableCreate(1L, "create_t1", Tbl1)
        MigrationRunner.run(listOf(m1a))
        assertTrue(m1a.ran)

        val m1b = TableCreate(1L, "create_t1", Tbl1)
        val m2 = TableCreate(2L, "create_t2", Tbl2)
        MigrationRunner.run(listOf(m1b, m2))
        assertFalse(m1b.ran, "V1 must not be applied twice")
        assertTrue(m2.ran)

        db {
            val versions = MigrationHistoryTable.selectAll()
                .orderBy(MigrationHistoryTable.version)
                .map { it[MigrationHistoryTable.version] }
            assertEquals(listOf(1L, 2L), versions)
        }
    }

    @Test
    fun `failing migration rolls back and does not record history`() {
        val m1 = TableCreate(1L, "create_t1", Tbl1)
        val m2 = AlwaysFails(2L, "fails")

        assertFailsWith<IllegalStateException> {
            MigrationRunner.run(listOf(m1, m2))
        }

        db {
            assertTrue(
                MigrationHistoryTable.selectAll()
                    .where { MigrationHistoryTable.version eq 1L }
                    .any()
            )
            assertFalse(
                MigrationHistoryTable.selectAll()
                    .where { MigrationHistoryTable.version eq 2L }
                    .any()
            )
            assertFalse(
                SchemaUtils.listTables().any { it.equals(Tbl2.tableName, ignoreCase = true) },
                "Tbl2 must be rolled back when V2 fails"
            )
        }
    }
}
