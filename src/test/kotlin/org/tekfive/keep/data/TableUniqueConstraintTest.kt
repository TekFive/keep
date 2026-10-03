package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.migration.dynamic.AddConstraint
import org.tekfive.keep.migration.dynamic.PostgresMigrationGenerator
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresFreshInstallGenerator
import org.tekfive.keep.schema.PostgresUniqueConstraintDefinition
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val UNIQUE_SCHEMA = "keep_table_unique_test"

class DirectUniqueData(val tenantId: Long?, val externalId: String?) : UuidData()
class DirectUniqueTable(name: String, named: Boolean, nullsNotDistinct: Boolean) : UuidDataTable<DirectUniqueData>(name) {
    val tenantId = column(DirectUniqueData::tenantId)
    val externalId = column(DirectUniqueData::externalId)

    init {
        if (named) uniqueConstraint("tenant_external_uq", tenantId, externalId, nullsNotDistinct = nullsNotDistinct)
        else uniqueConstraint(tenantId, externalId, nullsNotDistinct = nullsNotDistinct)
    }
}

class TableUniqueConstraintTest {
    @Test
    fun `named and inferred declarations register typed constraints without table hooks`() {
        val named = DirectUniqueTable("$UNIQUE_SCHEMA.records", true, true)
        val inferred = DirectUniqueTable("$UNIQUE_SCHEMA.records", false, true)
        val definition = named.columnPostgresObjects.single() as PostgresUniqueConstraintDefinition
        assertEquals("tenant_external_uq", definition.name)
        assertEquals(listOf(named.tenantId, named.externalId), definition.columns)
        assertTrue(definition.nullsNotDistinct)
        assertEquals("records_tenant_id_external_id_uq", inferred.columnPostgresObjects.single().name)
        assertTrue(named.postgresObjects.isEmpty())
        val schema = object : KeepSchema(UNIQUE_SCHEMA) { override val tables = listOf(named) }
        val sql = PostgresFreshInstallGenerator.plan(schema).statements
        assertTrue(sql.any { it.contains("UNIQUE NULLS NOT DISTINCT (\"tenant_id\", \"external_id\")") })
    }

    @Test
    fun `Long tables accept additional columns and existing index overload remains available`() {
        val table = object : DataTable<Data>("records") {
            val first = long("first")
            val second = text("second").nullable()
            val third = integer("third")
        }
        table.uniqueConstraint(table.first, table.second, table.third, nullsNotDistinct = true)
        val definition = table.columnPostgresObjects.single() as PostgresUniqueConstraintDefinition
        assertEquals(listOf(table.first, table.second, table.third), definition.columns)
        table.uniqueConstraint(table.first, table.third)
        assertEquals(1, table.indices.size)
        assertEquals(1, table.columnPostgresObjects.size)
    }

    @Test
    fun `named constraint defaults to distinct nulls and rejects invalid declarations`() {
        val table = object : UuidDataTable<DirectUniqueData>("records") {
            val tenantId = long("tenant_id").nullable()
        }
        table.uniqueConstraint("tenant_uq", table.tenantId)
        assertFalse((table.columnPostgresObjects.single() as PostgresUniqueConstraintDefinition).nullsNotDistinct)
        assertFailsWith<IllegalArgumentException> { table.uniqueConstraint("empty_uq", nullsNotDistinct = true) }
        assertFailsWith<IllegalArgumentException> { table.uniqueConstraint("duplicate_uq", table.tenantId, table.tenantId) }
        val other = object : Table("other") { val value = long("value") }
        assertFailsWith<IllegalArgumentException> { table.uniqueConstraint("foreign_uq", table.tenantId, other.value) }
        assertEquals(1, table.columnPostgresObjects.size)
    }
}

class TableUniqueConstraintIntegrationTest {
    private lateinit var database: Database

    @BeforeTest fun setup() { database = TestDatabase.connect() }
    @AfterTest fun cleanup() { transaction(database) { exec("DROP SCHEMA IF EXISTS $UNIQUE_SCHEMA CASCADE") } }

    private fun schema(table: Table) = object : KeepSchema(UNIQUE_SCHEMA) {
        override val tables = listOf(table)
    }

    private fun verifyNullUniqueness(table: DirectUniqueTable) {
        val values = listOf(1L to null, 2L to null, null to "a", null to "b", null to null, 1L to "a")
        transaction(database) {
            values.forEach { (tenant, external) -> table.create(DirectUniqueData(tenant, external)) }
        }
        values.forEach { (tenant, external) ->
            val failure = assertFailsWith<SQLException> {
                transaction(database) { table.create(DirectUniqueData(tenant, external)) }
            }
            assertEquals("23505", failure.sqlState)
        }
    }

    @Test
    fun `fresh install enforces named multi-column uniqueness including null combinations`() {
        val table = DirectUniqueTable("$UNIQUE_SCHEMA.records", true, true)
        val schema = schema(table)
        val plan = PostgresFreshInstallGenerator.plan(schema)
        transaction(database) { plan.statements.forEach { exec(it) } }
        verifyNullUniqueness(table)
        val migration = PostgresMigrationGenerator.plan(database, schema, true)
        assertTrue(migration.isEmpty, migration.toSql())
    }

    @Test
    fun `dynamic migration installs inferred constraints and updates null semantics`() {
        val original = DirectUniqueTable("$UNIQUE_SCHEMA.records", false, false)
        PostgresMigrationGenerator.plan(database, schema(original), true).execute(database)
        val desired = DirectUniqueTable("$UNIQUE_SCHEMA.records", false, true)
        val schema = schema(desired)
        val plan = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(1, plan.statements.filterIsInstance<AddConstraint>().size)
        plan.execute(database)
        verifyNullUniqueness(desired)
        val repeated = PostgresMigrationGenerator.plan(database, schema, true)
        assertTrue(repeated.isEmpty, repeated.toSql())
    }
}
