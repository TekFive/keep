package org.tekfive.keep.text

import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.tekfive.keep.data.Data
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.data.column
import org.tekfive.keep.data.dataProperty
import org.tekfive.keep.migration.dynamic.PostgresMigrationGenerator
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresFreshInstallGenerator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object SearchDocuments : Table("fts_documents") {
    val id = integer("id")
    val body = text("body")
    val optionalBody = text("optional_body").nullable()
    val vector = tsVector("search_vector")
    val optionalVector = tsVector("optional_vector").nullable()
    val savedQuery = tsQuery("saved_query").default(TsQuery("'cat'"))
    override val primaryKey = PrimaryKey(id)
    init { index("fts_documents_search_gin", false, vector, indexType = "GIN") }
}

class SearchData(var vector: TsVector, var optional: TsVector?, val query: TsQuery, val optionalQuery: TsQuery?) : Data()
object SearchDataTable : DataTable<SearchData>("fts_data", "fts_data_ids") {
    val stored = column(SearchData::vector, name = "stored_vector")
    val optionalStored = column(SearchData::optional)
    val storedQuery = column(SearchData::query)
    val optionalQuery = column(SearchData::optionalQuery)
}

class TextSearchIntegrationTest {
    private lateinit var database: Database

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) {
            SchemaUtils.create(SearchDocuments, SearchDataTable)
            listOf("Cats chase rats", "Cats like fish", "Dogs chase cats", "").forEachIndexed { index, text ->
                SearchDocuments.insert {
                    it[id] = index + 1
                    it[body] = text
                    it[optionalBody] = if (index == 0) text else null
                    it[vector] = toTsVector(text, "english")
                    it[optionalVector] = if (index == 0) TsVector("'cat':1 'chase':2 'rat':3") else null
                }
            }
        }
    }

    @AfterTest
    fun cleanup(): Unit = transaction(database) {
        SchemaUtils.drop(SearchDocuments, SearchDataTable)
        exec("DROP SCHEMA IF EXISTS fts_migration CASCADE")
    }

    @Test
    fun `native vector storage uses stemming and matches composable queries`(): Unit = transaction(database) {
        with(SearchDocuments) {
            assertEquals(TsVector("'cat':1 'chase':2 'rat':3"), selectAll().where { id eq 1 }.single()[vector])
            assertEquals(listOf(1), selectAll().where { vector matches plainToTsQuery("cat rat", "english") }.map { it[id] })
            assertEquals(listOf(1), selectAll().where { vector matches phraseToTsQuery("cats chase", "english") }.map { it[id] })
            assertEquals(listOf(2), selectAll().where { vector matches webSearchToTsQuery("cat -rat -dog", "english") }.map { it[id] })
            assertEquals(setOf(1, 3), selectAll().where { vector matches toTsQuery("rat | dog", "english") }.map { it[id] }.toSet())
            assertEquals(3, selectAll().where { vector matches toTsQuery("ca:*", "english") }.count())
            assertEquals(listOf(1), selectAll().where { vector matches TsQuery("'cat' & 'rat'") }.map { it[id] })
            assertEquals(listOf(2), selectAll().where {
                ((vector matches toTsQuery("fish", "english")) or (vector matches toTsQuery("dog", "english"))) and (id eq 2)
            }.map { it[id] })
            assertEquals(1, selectAll().where { not(vector matches plainToTsQuery("cat", "english")) }.count())
            assertEquals(0, selectAll().where { vector matches plainToTsQuery("", "english") }.count())
            assertEquals(TsQuery("'cat'"), selectAll().first()[savedQuery])
            assertEquals(3, selectAll().where { vector matches savedQuery }.count())
            exec("SELECT pg_typeof(search_vector)::text, pg_typeof(saved_query)::text FROM fts_documents LIMIT 1") {
                assertTrue(it.next())
                assertEquals("tsvector", it.getString(1))
                assertEquals("tsquery", it.getString(2))
            }
        }
    }

    @Test
    fun `text expressions support updates and null input remains null`(): Unit = transaction(database) {
        with(SearchDocuments) {
            update({ id eq 2 }) { it[vector] = toTsVector(body, "simple") }
            update { it[optionalVector] = toTsVector(optionalBody, "english") }
            assertEquals(listOf(2), selectAll().where { vector matches plainToTsQuery("cats", "simple") }.map { it[id] })
            assertEquals(listOf(1), selectAll().where { optionalVector matches plainToTsQuery("cat", "english") }.map { it[id] })
            val expression = toTsVector(optionalBody, "english")
            val query = plainToTsQuery("cat", "english")
            val rank = optionalVector.tsRank(query)
            val rankCd = optionalVector.tsRankCd(query)
            val nullRow = select(expression, rank, rankCd).where { id eq 2 }.single()
            assertNull(nullRow[expression])
            assertNull(nullRow[rank])
            assertNull(nullRow[rankCd])
        }
    }

    @Test
    fun `ranking returns floating point scores usable for ordering`(): Unit = transaction(database) {
        with(SearchDocuments) {
            val query = plainToTsQuery("cat", "english")
            val rank = vector.tsRank(query, normalization = 32)
            val density = vector.tsRankCd(query)
            val rows = select(id, rank, density).orderBy(rank to SortOrder.DESC).toList()
            assertTrue(rows.take(3).all { it[rank] > 0f && it[rank] < 1f && it[density] > 0f })
            assertEquals(4, rows.last()[id])
            assertEquals(0f, rows.last()[rank])
        }
    }

    @Test
    fun `default configuration follows server and query expressions map to TsQuery`(): Unit = transaction(database) {
        exec("SET LOCAL default_text_search_config = 'pg_catalog.english'")
        val vector = toTsVector("Cats")
        val query = plainToTsQuery("cats")
        val row = SearchDocuments.select(vector, query).limit(1).single()
        assertEquals(TsVector("'cat':1"), row[vector])
        assertEquals(TsQuery("'cat'"), row[query])
    }

    @Test
    fun `text and configuration are bound parameters`(): Unit = transaction(database) {
        val input = "cats'); DROP TABLE fts_documents; --"
        val configuration = "pg_catalog.english"
        val predicate = toTsVector(input, configuration) matches webSearchToTsQuery(input, configuration)
        val builder = QueryBuilder(true)
        predicate.toQueryBuilder(builder)
        assertFalse(builder.toString().contains(input))
        assertFalse(builder.toString().contains(configuration))
        assertEquals(listOf(configuration, input, configuration, input), builder.args.map { it.second })
        assertEquals(4, SearchDocuments.selectAll().where { predicate }.count())

        val maliciousConfig = "english'); DROP TABLE fts_documents; --"
        val configBuilder = QueryBuilder(true)
        toTsVector("cat", maliciousConfig).toQueryBuilder(configBuilder)
        assertFalse(configBuilder.toString().contains(maliciousConfig))
        assertEquals(maliciousConfig, configBuilder.args.first().second)
    }

    @Test
    fun `raw typed values round trip with parameters and escaped literals`(): Unit = transaction(database) {
        val value = TsVector("'a''b':1 'c\\\\d':2")
        SearchDocuments.update({ SearchDocuments.id eq 4 }) { it[vector] = value }
        val loaded = SearchDocuments.selectAll().where { SearchDocuments.id eq 4 }.single()[SearchDocuments.vector]
        exec("SET LOCAL standard_conforming_strings = off")
        exec("SELECT ${TsVectorColumnType().nonNullValueToString(value)}::text") {
            assertTrue(it.next())
            assertEquals(loaded.value, it.getString(1))
        }
        val rawQuery = TsQuery("'cat' & !'rat'")
        SearchDocuments.update({ SearchDocuments.id eq 4 }) { it[savedQuery] = rawQuery }
        val loadedQuery = SearchDocuments.selectAll().where { SearchDocuments.id eq 4 }.single()[SearchDocuments.savedQuery]
        exec("SELECT ${TsQueryColumnType().nonNullValueToString(rawQuery)}::text") {
            assertTrue(it.next())
            assertEquals(loadedQuery.value, it.getString(1))
        }
    }

    @Test
    fun `property declarations preserve data mapping and dirty tracking`() {
        val data = SearchData(TsVector("'cat':1"), TsVector(""), TsQuery("'cat'"), null)
        transaction(database) { SearchDataTable.create(data) }
        assertEquals(SearchData::vector, SearchDataTable.stored.dataProperty)
        assertEquals(SearchData::optional, SearchDataTable.optionalStored.dataProperty)
        transaction(database) {
            val loaded = SearchDataTable.getById(data.id)
            assertEquals(data.vector, loaded.vector)
            assertEquals(TsVector(""), loaded.optional)
            assertEquals(data.query, loaded.query)
            assertNull(loaded.optionalQuery)
            loaded.vector = TsVector("'dog':1")
            loaded.optional = null
            assertTrue(loaded.isDirty)
            SearchDataTable.save(loaded)
        }
        transaction(database) {
            val loaded = SearchDataTable.getById(data.id)
            assertEquals(TsVector("'dog':1"), loaded.vector)
            assertNull(loaded.optional)
        }
    }

    @Test
    fun `matches can use the declared GIN index`(): Unit = transaction(database) {
        exec("SET LOCAL enable_seqscan = off")
        val query = SearchDocuments.selectAll().where { SearchDocuments.vector matches plainToTsQuery("cat", "english") }
        val plan = mutableListOf<String>()
        exec("EXPLAIN ${query.prepareSQL(this, prepared = false)}", explicitStatementType = StatementType.SELECT) {
            while (it.next()) plan += it.getString(1)
        }
        assertTrue(plan.any { it.contains("fts_documents_search_gin") }, plan.joinToString("\n"))
    }

    @Test
    fun `fresh install and dynamic migrations retain vector type defaults and GIN index`() {
        val schemaName = "fts_migration"
        val table = object : Table("$schemaName.documents") {
            val vector = tsVector("vector").default(TsVector(""))
            val query = tsQuery("query").nullable()
            init { index("fts_migration_gin", false, vector, indexType = "GIN") }
        }
        val schema = object : KeepSchema(schemaName) { override val tables = listOf(table) }
        val fresh = PostgresFreshInstallGenerator.plan(schema)
        assertTrue(fresh.statements.any { it.contains("TSVECTOR", ignoreCase = true) })
        transaction(database) { fresh.statements.forEach { exec(it) } }
        val afterFresh = PostgresMigrationGenerator.plan(database, schema, nonDestructive = true)
        assertTrue(afterFresh.isEmpty, afterFresh.sqlStatements.joinToString("\n"))
        transaction(database) { exec("DROP SCHEMA $schemaName CASCADE") }
        val plan = PostgresMigrationGenerator.plan(database, schema, nonDestructive = true)
        assertFalse(plan.isEmpty)
        plan.execute(database)
        val afterDynamic = PostgresMigrationGenerator.plan(database, schema, nonDestructive = true)
        assertTrue(afterDynamic.isEmpty, afterDynamic.sqlStatements.joinToString("\n"))
        transaction(database) {
            table.insert { it[table.query] = null }
            val row = table.selectAll().single()
            assertEquals(TsVector(""), row[table.vector])
            assertNull(row[table.query])
        }
    }
}
