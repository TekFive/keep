package org.tekfive.keep.text

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.tekfive.keep.data.Data
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.data.UuidData
import org.tekfive.keep.data.UuidDataTable
import org.tekfive.keep.data.column
import org.tekfive.keep.data.dataProperty
import org.tekfive.keep.migration.dynamic.PostgresMigrationGenerator
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresFreshInstallGenerator
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NormalizedTextData(var handle: String, var optional: String?, var code: String, var ci: String) : Data()
object NormalizedTextDataTable : DataTable<NormalizedTextData>("normalized_text_data", "normalized_text_ids") {
    val account = column(NormalizedTextData::handle, name = "account_name")
        .normalizeText(trim = true, letterCase = TextCase.LOWER).uniqueIndex()
    val optionalValue = column(NormalizedTextData::optional, name = "optional_value")
        .normalizeText(trim = true, letterCase = TextCase.UPPER)
    val country = column(NormalizedTextData::code, name = "country_code", maxSize = 2)
        .normalizeText(trim = true, letterCase = TextCase.UPPER)
    val insensitive = column(NormalizedTextData::ci, name = "ci_value", caseInsensitive = true, maxSize = 8)
        .normalizeText(trim = true, letterCase = TextCase.LOWER)
}

class NormalizedUuidData(var label: String, var optional: String?) : UuidData()
object NormalizedUuidTable : UuidDataTable<NormalizedUuidData>("normalized_uuid_data") {
    val value = column(NormalizedUuidData::label, name = "stored_label").normalizeText(trim = true, letterCase = TextCase.UPPER)
    val optionalValue = column(NormalizedUuidData::optional, name = "stored_optional").normalizeText(trim = true)
}

private object NormalizedPlainTable : Table("normalized_plain_text") {
    val id = integer("id")
    val value = text("value").normalizeText(trim = true, letterCase = TextCase.LOWER)
    val optional = text("optional").normalizeText(trim = true).nullable()
    val defaulted = text("defaulted").clientDefault { " DEFAULT " }.normalizeText(trim = true)
    override val primaryKey = PrimaryKey(id)
}

class TextNormalizationIntegrationTest {
    private lateinit var database: Database

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) {
            exec("CREATE EXTENSION IF NOT EXISTS citext")
            SchemaUtils.create(NormalizedTextDataTable, NormalizedUuidTable, NormalizedPlainTable)
        }
    }

    @AfterTest
    fun cleanup() = transaction(database) {
        SchemaUtils.drop(NormalizedPlainTable, NormalizedUuidTable, NormalizedTextDataTable)
    }

    @Test
    fun `Long data mapping normalizes inserts and updates without changing the supplied object`() {
        val value = NormalizedTextData(" Alice ", " extra ", " us ", " MiXeD ")
        transaction(database) { NormalizedTextDataTable.create(value) }
        assertEquals(" Alice ", value.handle)
        assertFalse(value.isDirty)
        assertEquals(NormalizedTextData::handle, NormalizedTextDataTable.account.dataProperty)
        transaction(database) {
            val loaded = NormalizedTextDataTable.getById(value.id)
            assertEquals("alice", loaded.handle)
            assertEquals("EXTRA", loaded.optional)
            assertEquals("US", loaded.code)
            assertEquals("mixed", loaded.ci)
            exec("SELECT account_name, country_code, ci_value FROM normalized_text_data") { result ->
                assertTrue(result.next())
                assertEquals("alice", result.getString(1))
                assertEquals("US", result.getString(2))
                assertEquals("mixed", result.getString(3))
            }
        }
        value.handle = " BOB "
        value.optional = "  "
        transaction(database) { NormalizedTextDataTable.save(value) }
        transaction(database) {
            val loaded = NormalizedTextDataTable.getById(value.id)
            assertEquals("bob", loaded.handle)
            assertEquals("", loaded.optional)
        }
    }

    @Test
    fun `UUID data mapping preserves null and tracks normalized saves`() {
        val value = NormalizedUuidData(" first ", null)
        transaction(database) { NormalizedUuidTable.create(value) }
        transaction(database) {
            val loaded = NormalizedUuidTable.getById(value.id)
            assertEquals("FIRST", loaded.label)
            assertNull(loaded.optional)
            loaded.label = " second "
            loaded.optional = " trimmed "
            NormalizedUuidTable.save(loaded)
        }
        transaction(database) {
            val loaded = NormalizedUuidTable.getById(value.id)
            assertEquals("SECOND", loaded.label)
            assertEquals("trimmed", loaded.optional)
        }
    }

    @Test
    fun `eq neq inList and citext bindings use normalized parameters`() = transaction(database) {
        NormalizedTextDataTable.create(NormalizedTextData(" Alice ", null, "us", " MiXeD "))
        with(NormalizedTextDataTable) {
            assertEquals(1, selectAll().where { account eq " ALICE " }.count())
            assertEquals(0, selectAll().where { account neq " ALICE " }.count())
            assertEquals(1, selectAll().where { account inList listOf(" nobody ", " ALICE ") }.count())
            assertEquals(1, selectAll().where { insensitive eq " MIXED " }.count())
            // Explicit SQL literals bypass normalization but CITEXT still compares without case sensitivity.
            assertEquals(1, selectAll().where { insensitive eq stringLiteral("MIXED") }.count())
        }
    }

    @Test
    fun `plain table batch writes and client defaults normalize while patterns remain literal`() = transaction(database) {
        with(NormalizedPlainTable) {
            batchInsert(listOf(" ALPHA ", " BETA ")) { raw ->
                this[id] = if (raw.contains("ALPHA")) 1 else 2
                this[value] = raw
            }
            assertEquals(setOf("alpha", "beta"), selectAll().map { it[value] }.toSet())
            assertTrue(selectAll().all { it[defaulted] == "DEFAULT" && it[optional] == null })
            update({ id eq 1 }) { it[value] = " GAMMA " }
            assertEquals(1, selectAll().where { value like "g%" }.count())
            assertEquals(0, selectAll().where { value like " G% " }.count())
            assertEquals(1, selectAll().where { value ilike "G*" }.count())
            assertEquals(0, selectAll().where { value ilike " G* " }.count())
        }
    }

    @Test
    fun `raw SQL and expression updates bypass normalization and reads preserve stored text`() = transaction(database) {
        exec("INSERT INTO normalized_plain_text (id, value, defaulted) VALUES (1, ' Legacy ', 'default')")
        with(NormalizedPlainTable) {
            assertEquals(" Legacy ", selectAll().single()[value])
            update({ id eq 1 }) { it[value] = stringLiteral(" Expression ") }
            assertEquals(" Expression ", selectAll().single()[value])
        }
    }

    @Test
    fun `unique indexes reject equivalent normalized inputs`() {
        transaction(database) { NormalizedTextDataTable.create(NormalizedTextData(" Alice ", null, "us", "a")) }
        val failure = assertFailsWith<SQLException> {
            transaction(database) {
                maxAttempts = 1
                NormalizedTextDataTable.create(NormalizedTextData("ALICE", null, "us", "b"))
            }
        }
        assertEquals("23505", failure.sqlState)
    }

    @Test
    fun `writes enforce normalized varchar and citext lengths`() {
        transaction(database) {
            val value = NormalizedTextData(" expansion ", null, " ß ", " PADDED ")
            NormalizedTextDataTable.create(value)
            assertEquals("SS", NormalizedTextDataTable.selectAll().single()[NormalizedTextDataTable.country])
        }
        assertFailsWith<IllegalArgumentException> {
            transaction(database) {
                NormalizedTextDataTable.create(NormalizedTextData("bad-country", null, " ßs ", "ok"))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            transaction(database) {
                NormalizedTextDataTable.create(NormalizedTextData("bad-citext", null, "us", " 123456789 "))
            }
        }
        transaction(database) { assertEquals(1, NormalizedTextDataTable.selectAll().count()) }
    }

    @Test
    fun `normalization preserves schema storage and generates no migration`() {
        val schemaName = "keep_normalization_schema"
        fun table(normalized: Boolean) = object : Table("$schemaName.names") {
            val value = varchar("value", 20).let { if (normalized) it.normalizeText(trim = true, letterCase = TextCase.LOWER) else it }
        }
        val original = object : KeepSchema(schemaName) { override val tables = listOf(table(false)) }
        val normalized = object : KeepSchema(schemaName) { override val tables = listOf(table(true)) }
        try {
            assertEquals(PostgresFreshInstallGenerator.plan(original).statements, PostgresFreshInstallGenerator.plan(normalized).statements)
            transaction(database) { PostgresFreshInstallGenerator.plan(original).statements.forEach { exec(it) } }
            assertTrue(PostgresMigrationGenerator.plan(database, normalized, nonDestructive = true).isEmpty)
        } finally {
            transaction(database) { exec("DROP SCHEMA IF EXISTS $schemaName CASCADE") }
        }
    }
}
