package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.Data
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.migration.PostgresMigrationGenerator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val FRESH_SCHEMA = "keep_fresh_install_test"

private object FreshAccounts : Table("$FRESH_SCHEMA.accounts") {
    val id = long("id")
    val name = varchar("name", 120).uniqueIndex("accounts_name_uq")
    val enabled = bool("enabled").default(true)
    override val primaryKey = PrimaryKey(id)

    init {
        check("accounts_name_not_empty") { name neq "" }
    }
}

private object FreshSessions : Table("$FRESH_SCHEMA.sessions") {
    val id = javaUUID("id")
    val accountId = long("account_id").references(
        FreshAccounts.id,
        onDelete = ReferenceOption.CASCADE,
        fkName = "sessions_account_id_fk",
    ).index("sessions_account_id_idx")
    override val primaryKey = PrimaryKey(id)
}

private object FreshInstallSchema : KeepSchema(FRESH_SCHEMA) {
    override val tables = listOf(FreshSessions, FreshAccounts)
    override val sequenceDefinitions = listOf(
        PostgresSequenceDefinition(
            name = "install_number_seq",
            startWith = 100,
            incrementBy = 5,
            cache = 10,
        )
    )
    override val beforeTablesSql = listOf(
        "CREATE TYPE $FRESH_SCHEMA.install_state AS ENUM ('ready', 'complete')"
    )
    override val afterTablesSql = listOf(
        "COMMENT ON TABLE $FRESH_SCHEMA.accounts IS 'fresh install account table'"
    )
    override val views = listOf(
        PostgresViewDefinition(
            name = "enabled_accounts",
            query = "SELECT id, name FROM $FRESH_SCHEMA.accounts WHERE enabled",
        ),
        PostgresViewDefinition(
            name = "account_count",
            query = "SELECT count(*) AS total FROM $FRESH_SCHEMA.accounts",
            materialized = true,
        ),
    )
}

private class FreshHookData(val label: String) : Data()

private object FreshHookTable : DataTable<FreshHookData>("fresh_hook_table") {
    val label = varchar("label", 100)
    override val postgresObjects = postgresObjects {
        uniqueConstraint("fresh_hook_label_uq", label)
    }
    override val customTypes = listOf("CREATE TYPE fresh_hook_type AS ENUM ('one', 'two')")
    override val customIndices = listOf("CREATE INDEX fresh_hook_label_idx ON fresh_hook_table(label)")
    override val postSchemaCreateSql = listOf("COMMENT ON TABLE fresh_hook_table IS 'hook table'")
}

private object FreshHookSchema : AppSchema() {
    override val extensions = listOf("citext")
    override val tables = listOf(FreshHookTable)
}

class PostgresFreshInstallGeneratorOfflineTest {
    @Test
    fun `plan renders a complete deterministic script without a database`() {
        val first = PostgresFreshInstallGenerator.plan(FreshInstallSchema)
        val second = PostgresFreshInstallGenerator.plan(FreshInstallSchema)

        assertEquals(first, second)
        assertContains(first.statements, "CREATE SCHEMA IF NOT EXISTS \"$FRESH_SCHEMA\"")
        assertTrue(first.statements.any { it.startsWith("CREATE TYPE") })
        assertTrue(first.statements.any { it.startsWith("CREATE SEQUENCE") && it.contains("install_number_seq") })
        assertTrue(first.statements.any { it.startsWith("CREATE TABLE") && it.contains("accounts") })
        assertTrue(first.statements.any { it.startsWith("CREATE TABLE") && it.contains("sessions") })
        assertTrue(first.statements.any { it.contains("sessions_account_id_fk") })
        assertTrue(first.statements.any { it.contains("accounts_name_uq") })
        assertTrue(first.statements.any { it.startsWith("CREATE VIEW") })
        assertTrue(first.statements.any { it.startsWith("CREATE MATERIALIZED VIEW") })
        assertTrue(first.toSql().endsWith(";\n"))
    }

    @Test
    fun `plan puts referenced tables before referencing tables`() {
        val tableStatements = PostgresFreshInstallGenerator.plan(FreshInstallSchema).statements
            .filter { it.startsWith("CREATE TABLE") }

        assertTrue(tableStatements[0].contains("accounts"))
        assertTrue(tableStatements[1].contains("sessions"))
    }

    @Test
    fun `AppSchema extensions sequences and table hooks are included once in order`() {
        val statements = PostgresFreshInstallGenerator.plan(FreshHookSchema).statements
        val extensionIndex = statements.indexOfFirst { it.startsWith("CREATE EXTENSION") }
        val typeIndex = statements.indexOfFirst { it.startsWith("CREATE TYPE") }
        val sequenceIndices = statements.indices.filter { statements[it].startsWith("CREATE SEQUENCE") }
        val tableIndex = statements.indexOfFirst { it.startsWith("CREATE TABLE") }
        val customIndex = statements.indexOfFirst { it.contains("fresh_hook_label_idx") }
        val typedConstraint = statements.indexOfFirst { it.contains("fresh_hook_label_uq") }
        val commentIndex = statements.indexOfFirst { it.startsWith("COMMENT ON TABLE") }

        assertEquals(1, sequenceIndices.size)
        assertTrue(extensionIndex < typeIndex)
        assertTrue(typeIndex < sequenceIndices.single())
        assertTrue(sequenceIndices.single() < tableIndex)
        assertTrue(tableIndex < customIndex)
        assertTrue(customIndex < typedConstraint)
        assertTrue(typedConstraint < commentIndex)
    }

    @Test
    fun `fresh schema rejects undeclared foreign key targets`() {
        val incomplete = object : KeepSchema(FRESH_SCHEMA) {
            override val tables = listOf(FreshSessions)
        }

        val error = assertFailsWith<IllegalArgumentException> {
            PostgresFreshInstallGenerator.plan(incomplete)
        }

        assertTrue(error.message.orEmpty().contains("undeclared table"))
    }
}

class PostgresFreshInstallGeneratorIntegrationTest {
    private lateinit var database: Database

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) { exec("DROP SCHEMA IF EXISTS $FRESH_SCHEMA CASCADE") }
    }

    @AfterTest
    fun teardown() {
        transaction(database) { exec("DROP SCHEMA IF EXISTS $FRESH_SCHEMA CASCADE") }
    }

    @Test
    fun `generated SQL installs into an empty PostgreSQL database`() {
        val install = PostgresFreshInstallGenerator.plan(FreshInstallSchema)

        transaction(database) {
            install.statements.forEach { exec(it) }
            assertTrue(FreshAccounts.selectAll().empty())
        }

        val merge = PostgresMigrationGenerator.plan(database, FreshInstallSchema, nonDestructive = false)
        assertTrue(merge.isEmpty, "Fresh install did not match KeepSchema: $merge")
    }
}
