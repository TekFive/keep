package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.Data
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.migration.PostgresMigrationGenerator
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val NULLS_SCHEMA = "keep_nulls_unique_test"

class NullsUniqueData(val scope: String, val code: String?) : Data()

private class NullsUniqueTable(nullsNotDistinct: Boolean, composite: Boolean = true) :
    DataTable<NullsUniqueData>("$NULLS_SCHEMA.entries", idSequenceName = "entries_id_seq") {
    val scope = text("scope")
    val code = text("code").nullable()

    override val postgresObjects = postgresObjects {
        val keyColumns = if (composite) arrayOf(scope, code) else arrayOf(code)
        uniqueConstraint("entries_key", *keyColumns, nullsNotDistinct = nullsNotDistinct)
    }
}

private fun nullsSchema(nullsNotDistinct: Boolean, composite: Boolean = true) = object : AppSchema(NULLS_SCHEMA) {
    override val tables = listOf(NullsUniqueTable(nullsNotDistinct, composite))
    override val sequences = listOf("entries_id_seq")
}

class NullsNotDistinctOfflineTest {
    @Test
    fun `renders table-owned single and composite constraints`() {
        for (composite in listOf(false, true)) {
            val statements = PostgresFreshInstallGenerator.plan(nullsSchema(true, composite)).statements
            val columns = if (composite) "\"scope\", \"code\"" else "\"code\""
            assertTrue(statements.contains(
                "ALTER TABLE \"$NULLS_SCHEMA\".\"entries\" ADD CONSTRAINT \"entries_key\" " +
                    "UNIQUE NULLS NOT DISTINCT ($columns)"
            ))
        }
    }

    @Test
    fun `rejects nulls not distinct before PostgreSQL 15 but preserves ordinary unique`() {
        for (major in 12..14) {
            val error = assertFailsWith<IllegalArgumentException> {
                PostgresFreshInstallGenerator.plan(nullsSchema(true), PostgresTargetVersion(major))
            }
            assertTrue(error.message!!.contains("requires PostgreSQL 15"))
            val ordinary = PostgresFreshInstallGenerator.plan(nullsSchema(false), PostgresTargetVersion(major))
            assertTrue(ordinary.statements.any { it.endsWith("UNIQUE (\"scope\", \"code\")") })
        }
        assertTrue(PostgresFreshInstallGenerator.plan(nullsSchema(true), PostgresTargetVersion(15))
            .statements.any { it.contains("UNIQUE NULLS NOT DISTINCT") })
    }
}

class NullsNotDistinctIntegrationTest {
    private lateinit var database: Database

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) { exec("DROP SCHEMA IF EXISTS $NULLS_SCHEMA CASCADE") }
    }

    @AfterTest
    fun teardown() {
        transaction(database) { exec("DROP SCHEMA IF EXISTS $NULLS_SCHEMA CASCADE") }
    }

    private fun execute(sql: String) = transaction(database) { exec(sql) }

    @Test
    fun `fresh install enforces composite null equality per complete key`() {
        val schema = nullsSchema(true)
        transaction(database) {
            PostgresFreshInstallGenerator.plan(schema).statements.forEach { exec(it) }
        }
        execute("INSERT INTO $NULLS_SCHEMA.entries (id, scope, code) VALUES (1, 'a', NULL), (2, 'b', NULL)")
        val error = assertFailsWith<SQLException> {
            execute("INSERT INTO $NULLS_SCHEMA.entries (id, scope, code) VALUES (3, 'a', NULL)")
        }
        assertEquals("23505", error.sqlState)
        execute("INSERT INTO $NULLS_SCHEMA.entries (id, scope, code) VALUES (4, 'a', 'x')")
        assertFailsWith<SQLException> {
            execute("UPDATE $NULLS_SCHEMA.entries SET code = NULL WHERE id = 4")
        }
        PostgresMigrationGenerator.plan(database, schema, true).let {
            assertTrue(it.isEmpty, it.toString())
        }
    }

    @Test
    fun `app schema create enforces a single null for single column constraints`() {
        val schema = nullsSchema(true, composite = false)
        transaction(database) {
            exec("CREATE SCHEMA $NULLS_SCHEMA")
            exec("SET LOCAL search_path TO $NULLS_SCHEMA, public")
            schema.create()
        }
        execute("INSERT INTO $NULLS_SCHEMA.entries (id, scope, code) VALUES (1, 'a', NULL)")
        assertFailsWith<SQLException> {
            execute("INSERT INTO $NULLS_SCHEMA.entries (id, scope, code) VALUES (2, 'b', NULL)")
        }
        PostgresMigrationGenerator.plan(database, schema, true).let {
            assertTrue(it.isEmpty, it.toString())
        }
    }

    @Test
    fun `migration detects both null treatment changes and is idempotent`() {
        val ordinary = nullsSchema(false)
        transaction(database) {
            PostgresFreshInstallGenerator.plan(ordinary).statements.forEach { exec(it) }
        }
        execute("INSERT INTO $NULLS_SCHEMA.entries (id, scope, code) VALUES (1, 'a', NULL), (2, 'a', NULL)")
        execute("DELETE FROM $NULLS_SCHEMA.entries WHERE id = 2")

        for (distinct in listOf(true, false)) {
            val schema = nullsSchema(distinct)
            val migration = PostgresMigrationGenerator.plan(database, schema, nonDestructive = true)
            assertTrue(migration.statements.any { it.contains("DROP CONSTRAINT \"entries_key\"") })
            val add = migration.statements.single { it.contains("ADD CONSTRAINT \"entries_key\"") }
            assertEquals(distinct, add.contains("NULLS NOT DISTINCT"))
            transaction(database) { migration.statements.forEach { exec(it) } }
            PostgresMigrationGenerator.plan(database, schema, true).let {
                assertTrue(it.isEmpty, it.toString())
            }
        }
        execute("INSERT INTO $NULLS_SCHEMA.entries (id, scope, code) VALUES (2, 'a', NULL)")
    }
}
