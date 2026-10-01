package org.tekfive.keep.json

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.json.contains
import org.jetbrains.exposed.v1.json.exists
import org.jetbrains.exposed.v1.json.extract
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.data.UuidData
import org.tekfive.keep.data.UuidDataTable
import org.tekfive.keep.data.column
import org.tekfive.keep.db.dbConnection
import org.tekfive.keep.job.db.JsonPathOperator
import org.tekfive.keep.migration.dynamic.CreateIndex
import org.tekfive.keep.migration.dynamic.DropIndex
import org.tekfive.keep.migration.dynamic.PostgresMigrationGenerator
import org.tekfive.keep.migration.dynamic.SqlExpression
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresFreshInstallGenerator
import org.tekfive.keep.schema.PostgresIndexKey
import org.tekfive.keep.schema.postgresObjects
import java.sql.PreparedStatement
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val SCHEMA = "keep_jsonb_gin_test"
private const val PAYLOAD_INDEX = "documents_payload_idx"
private const val ITEMS_INDEX = "documents_items_idx"
private const val TAGS_INDEX = "documents_tags_idx"

class JsonbGinDocument(var payload: JsonObject?, var items: List<JsonObject>) : UuidData()

class JsonbGinIntegrationTest {
    private lateinit var database: Database
    private var payloadMethod = "gin"
    private var withPayloadIndex = true
    private var payloadPredicate: SqlExpression? = null
    private var withTagsIndex = false
    private val documents = object : UuidDataTable<JsonbGinDocument>("$SCHEMA.documents") {
        val payload = column(JsonbGinDocument::payload)
        val items = column(JsonbGinDocument::items)
        override val postgresObjects get() = postgresObjects {
            if (withPayloadIndex) index(PAYLOAD_INDEX, payload, method = payloadMethod, predicate = payloadPredicate)
            index(ITEMS_INDEX, items, method = "gin")
            if (withTagsIndex) index(TAGS_INDEX,
                listOf(PostgresIndexKey.ExpressionKey(SqlExpression("payload -> 'tags'"))), method = "gin")
        }
    }
    private val schema = object : KeepSchema(SCHEMA) { override val tables = listOf(documents) }

    @BeforeTest fun setup() { database = TestDatabase.connect() }
    @AfterTest fun cleanup() { transaction(database) { exec("DROP SCHEMA IF EXISTS $SCHEMA CASCADE") } }

    private fun installAndSeed() {
        PostgresMigrationGenerator.plan(database, schema, true).execute(database)
        transaction(database) {
            val payloads = listOf(
                """{"status":"active","profile":{"owner":"O'Reilly"},"tags":["urgent","blue"],"score":12}""",
                """{"status":"inactive","tags":["blue"],"score":2}""",
                """{"status":"active","profile":{"owner":"other"},"tags":["urgent"]}""",
                null,
                "{}",
            )
            payloads.forEachIndexed { index, json ->
                documents.insert {
                    it[id] = UUID(0, index + 1L)
                    it[payload] = json?.asRequiredJsonObject()
                    it[items] = when (index) {
                        0 -> listOf("""{"sku":"A","quantity":2}""".asRequiredJsonObject())
                        1 -> listOf("""{"sku":"B"}""".asRequiredJsonObject())
                        else -> emptyList()
                    }
                }
            }
            exec("ANALYZE $SCHEMA.documents")
        }
    }

    @Test fun `fresh installation creates default JSONB GIN indexes recognized by dynamic migration`() {
        val fresh = PostgresFreshInstallGenerator.plan(schema)
        transaction(database) {
            fresh.statements.forEach { exec(it) }
            val indexes = exec("""
                SELECT ci.relname, am.amname, op.opcname
                FROM pg_index i JOIN pg_class ci ON ci.oid = i.indexrelid
                JOIN pg_namespace n ON n.oid = ci.relnamespace
                JOIN pg_am am ON am.oid = ci.relam
                JOIN pg_opclass op ON op.oid = i.indclass[0]
                WHERE n.nspname = '$SCHEMA' AND ci.relname IN ('$PAYLOAD_INDEX', '$ITEMS_INDEX')
            """.trimIndent()) { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2) to rows.getString(3)) }
            }
            assertEquals(mapOf(PAYLOAD_INDEX to ("gin" to "jsonb_ops"), ITEMS_INDEX to ("gin" to "jsonb_ops")), indexes)
        }
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
    }

    @Test fun `Exposed containment binds JSON strings and objects and uses the declared GIN index`() {
        installAndSeed()
        transaction(database) {
            val cases = listOf(
                documents.select(documents.id).where { documents.payload.contains("""{"status":"active"}""") } to setOf(1L, 3L),
                documents.select(documents.id).where {
                    documents.payload.contains("""{"profile":{"owner":"O'Reilly"}}""".asRequiredJsonObject())
                } to setOf(1L),
                documents.select(documents.id).where { documents.payload.contains("""{"tags":["urgent"]}""") } to setOf(1L, 3L),
                documents.select(documents.id).where { documents.payload.contains("""{"status":"missing"}""") } to emptySet(),
            )
            for ((query, expected) in cases) {
                assertEquals(expected, query.map { it[documents.id].leastSignificantBits }.toSet())
                assertUsesIndex(explain(query), PAYLOAD_INDEX)
            }
        }
    }

    @Test fun `JSON object list containment uses its GIN index and preserves list serialization`() {
        installAndSeed()
        transaction(database) {
            val query = documents.selectAll().where { documents.items.contains("""[{"sku":"A"}]""") }
            val row = query.single()
            assertEquals(UUID(0, 1), row[documents.id])
            assertEquals("A", row[documents.items].single().string("sku"))
            assertUsesIndex(explain(query), ITEMS_INDEX)
            assertEquals(5L, documents.selectAll().where { documents.items.contains("[]") }.count())
        }
    }

    @Test fun `native key existence and JSONPath operators can use the declared default GIN index`() {
        installAndSeed()
        transaction(database) {
            // These operators have no KEEP DSL wrappers yet. JDBC requires ?? for a literal ? operator.
            data class Case(val predicate: String, val expected: Set<Long>, val bind: (PreparedStatement) -> Unit)
            val cases = listOf(
                Case("payload ?? ?", setOf(1, 2, 3)) { it.setString(1, "status") },
                Case("payload ??| ?::text[]", setOf(1, 3)) {
                    it.setArray(1, dbConnection().createArrayOf("text", arrayOf("profile", "missing")))
                },
                Case("payload ??& ?::text[]", setOf(1, 3)) {
                    it.setArray(1, dbConnection().createArrayOf("text", arrayOf("profile", "status")))
                },
                Case("payload @?? ?::jsonpath", setOf(1, 3)) { it.setString(1, """$.tags[*] ? (@ == "urgent")""") },
                Case("payload @@ ?::jsonpath", setOf(1, 3)) { it.setString(1, """$.status == "active"""") },
            )
            for (case in cases) {
                val sql = "SELECT id FROM $SCHEMA.documents WHERE ${case.predicate}"
                dbConnection().prepareStatement(sql).use { statement ->
                    case.bind(statement)
                    statement.executeQuery().use { rows ->
                        val matches = buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java).leastSignificantBits) }
                        assertEquals(case.expected, matches, case.predicate)
                    }
                }
                assertUsesIndex(explainNative(sql, case.bind), PAYLOAD_INDEX)
            }
        }
    }

    @Test fun `extracted comparisons and JSONPath functions do not use a whole document GIN index`() {
        installAndSeed()
        transaction(database) {
            val extracted = documents.select(documents.id).where { documents.payload.extract<String>("status") eq "active" }
            val exists = documents.select(documents.id).where { documents.payload.exists(".status") }
            assertEquals(setOf(1L, 3L), extracted.map { it[documents.id].leastSignificantBits }.toSet())
            assertEquals(setOf(1L, 2L, 3L), exists.map { it[documents.id].leastSignificantBits }.toSet())
            for (query in listOf(extracted, exists)) {
                val plan = explain(query)
                assertFalse(plan.contains(PAYLOAD_INDEX), plan)
                assertTrue(plan.contains("Seq Scan"), plan)
            }
            val operator = JsonPathOperator.Equals(listOf("status"), "active")
            val sql = "SELECT id FROM $SCHEMA.documents WHERE ${operator.toParameterizedSql("payload")}"
            val plan = explainNative(sql) { operator.addValue(AtomicInteger(1), it) }
            assertFalse(plan.contains(PAYLOAD_INDEX), plan)
            assertTrue(plan.contains("Seq Scan"), plan)
        }
    }

    @Test fun `dynamic migration replaces method predicate and operator class drift then removes the index`() {
        payloadMethod = "btree"
        installAndSeed()
        payloadMethod = "gin"
        val methodChange = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(listOf(DropIndex::class, CreateIndex::class), methodChange.statements.map { it::class })
        methodChange.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)

        payloadPredicate = SqlExpression("payload IS NOT NULL")
        val partial = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(listOf(DropIndex::class, CreateIndex::class), partial.statements.map { it::class })
        partial.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        transaction(database) {
            assertUsesIndex(explain(documents.select(documents.id).where {
                documents.payload.contains("""{"status":"active"}""")
            }), PAYLOAD_INDEX)
            // Operator classes currently require SQL or a low-level IndexDefinition.
            exec("DROP INDEX $SCHEMA.$PAYLOAD_INDEX")
            exec("CREATE INDEX $PAYLOAD_INDEX ON $SCHEMA.documents USING gin (payload jsonb_path_ops) WHERE payload IS NOT NULL")
        }
        val operatorClassChange = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(listOf(DropIndex::class, CreateIndex::class), operatorClassChange.statements.map { it::class })
        operatorClassChange.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        withPayloadIndex = false
        val removal = PostgresMigrationGenerator.plan(database, schema, true)
        assertEquals(PAYLOAD_INDEX, assertIs<DropIndex>(removal.statements.single()).name.name)
        removal.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        transaction(database) { assertEquals(5L, documents.selectAll().count()) }
    }

    @Test fun `a matching expression GIN index supports extracted JSON array membership`() {
        withTagsIndex = true
        installAndSeed()
        assertTrue(PostgresMigrationGenerator.plan(database, schema, true).isEmpty)
        transaction(database) {
            val plan = explainNative("SELECT id FROM $SCHEMA.documents WHERE (payload -> 'tags') ?? ?") {
                it.setString(1, "urgent")
            }
            assertUsesIndex(plan, TAGS_INDEX)
            assertFalse(plan.contains(PAYLOAD_INDEX), plan)
        }
    }

    private fun JdbcTransaction.explain(query: Query): String {
        // Small fixtures naturally favor sequential scans. Disable them only in this transaction
        // to test index eligibility, not cost estimates or performance. Keep actual Exposed bindings.
        exec("SET LOCAL enable_seqscan = off")
        return checkNotNull(exec(
            "EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF) ${query.prepareSQL(this, prepared = true)}",
            args = query.arguments().flatten(),
            explicitStatementType = StatementType.SELECT,
        ) { rows -> buildList { while (rows.next()) add(rows.getString(1)) }.joinToString("\n") })
    }

    private fun JdbcTransaction.explainNative(sql: String, bind: (PreparedStatement) -> Unit): String {
        exec("SET LOCAL enable_seqscan = off")
        return dbConnection().prepareStatement("EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF) $sql").use { statement ->
            bind(statement)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) }.joinToString("\n") }
        }
    }

    private fun assertUsesIndex(plan: String, name: String) {
        assertTrue(plan.contains("Bitmap Index Scan on $name"), plan)
        assertTrue(plan.contains("Index Cond:"), plan)
    }
}
