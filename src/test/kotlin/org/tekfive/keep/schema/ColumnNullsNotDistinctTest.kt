package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.Data
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.data.UuidData
import org.tekfive.keep.data.UuidDataTable
import org.tekfive.keep.data.column
import org.tekfive.keep.data.uniqueNonNullUniqueIndex
import org.tekfive.keep.migration.PostgresMigrationGenerator
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val COLUMN_NULLS_SCHEMA = "keep_column_nulls_test"

class ColumnUniqueArticle(val url: String?, val publisher: String) : Data()
class UuidColumnUniqueArticle(val url: String?) : UuidData()

private class ColumnUniqueArticles(useHelper: Boolean = true) :
    DataTable<ColumnUniqueArticle>("$COLUMN_NULLS_SCHEMA.articles", idSequenceName = "article_ids") {
    val url = column(ColumnUniqueArticle::url).let {
        if (useHelper) it.uniqueNonNullUniqueIndex("articles_url_uq") else it
    }
    val publisher = column(ColumnUniqueArticle::publisher)

    override val postgresObjects = postgresObjects {
        uniqueConstraint("articles_publisher_uq", publisher)
        if (!useHelper) uniqueConstraint("articles_url_uq", url)
    }
}

private class UuidColumnUniqueArticles : UuidDataTable<UuidColumnUniqueArticle>("$COLUMN_NULLS_SCHEMA.uuid_articles") {
    val url = column(UuidColumnUniqueArticle::url).uniqueNonNullUniqueIndex()
}

private fun columnNullsSchema(useHelper: Boolean = true) = object : AppSchema(COLUMN_NULLS_SCHEMA) {
    override val tables = listOf<Table>(ColumnUniqueArticles(useHelper), UuidColumnUniqueArticles())
    override val sequences = listOf("article_ids")
}

class ColumnNullsNotDistinctOfflineTest {
    @Test
    fun `column helpers coexist with table hooks on long and UUID tables`() {
        val schema = columnNullsSchema()
        val sql = PostgresFreshInstallGenerator.plan(schema).statements
        assertEquals(1, sql.count { it.contains("ADD CONSTRAINT \"articles_url_uq\" UNIQUE NULLS NOT DISTINCT") })
        assertEquals(1, sql.count { it.contains("ADD CONSTRAINT \"uuid_articles_url_uq\" UNIQUE NULLS NOT DISTINCT") })
        assertEquals(1, sql.count { it.contains("ADD CONSTRAINT \"articles_publisher_uq\" UNIQUE (") })
        assertFailsWith<IllegalArgumentException> {
            PostgresFreshInstallGenerator.plan(schema, PostgresTargetVersion(14))
        }
    }

    @Test
    fun `rejects plain exposed tables instead of silently omitting the constraint`() {
        val table = Table("plain_articles")
        val error = assertFailsWith<IllegalArgumentException> {
            with(table) { text("url").nullable().uniqueNonNullUniqueIndex() }
        }
        assertTrue(error.message!!.contains("requires a KEEP table"))
    }
}

class ColumnNullsNotDistinctIntegrationTest {
    private lateinit var database: Database

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) { exec("DROP SCHEMA IF EXISTS $COLUMN_NULLS_SCHEMA CASCADE") }
    }

    @AfterTest
    fun teardown() {
        transaction(database) { exec("DROP SCHEMA IF EXISTS $COLUMN_NULLS_SCHEMA CASCADE") }
    }

    @Test
    fun `app schema enforces helper uniqueness and migrates without further changes`() {
        val schema = columnNullsSchema()
        transaction(database) {
            exec("CREATE SCHEMA $COLUMN_NULLS_SCHEMA")
            exec("SET LOCAL search_path TO $COLUMN_NULLS_SCHEMA, public")
            schema.create()
            exec("INSERT INTO $COLUMN_NULLS_SCHEMA.articles (id, publisher, url) VALUES (1, 'one', NULL)")
            exec("INSERT INTO $COLUMN_NULLS_SCHEMA.articles (id, publisher, url) VALUES (2, 'two', 'https://example.com')")
            exec("INSERT INTO $COLUMN_NULLS_SCHEMA.uuid_articles (id, url) VALUES ('00000000-0000-0000-0000-000000000001', NULL)")
        }
        for (sql in listOf(
            "INSERT INTO $COLUMN_NULLS_SCHEMA.articles (id, publisher, url) VALUES (3, 'three', NULL)",
            "INSERT INTO $COLUMN_NULLS_SCHEMA.articles (id, publisher, url) VALUES (3, 'three', 'https://example.com')",
            "INSERT INTO $COLUMN_NULLS_SCHEMA.uuid_articles (id, url) VALUES ('00000000-0000-0000-0000-000000000002', NULL)",
        )) {
            val error = assertFailsWith<SQLException> { transaction(database) { exec(sql) } }
            assertEquals("23505", error.sqlState)
        }
        val plan = PostgresMigrationGenerator.plan(database, schema, true)
        assertTrue(plan.isEmpty, plan.toString())
    }

    @Test
    fun `migration changes existing ordinary uniqueness to column helper semantics`() {
        transaction(database) {
            PostgresFreshInstallGenerator.plan(columnNullsSchema(false)).statements.forEach { exec(it) }
        }
        val schema = columnNullsSchema()
        val plan = PostgresMigrationGenerator.plan(database, schema, true)
        assertTrue(plan.statements.any { it.contains("DROP CONSTRAINT \"articles_url_uq\"") })
        assertTrue(plan.statements.any { it.contains("ADD CONSTRAINT \"articles_url_uq\" UNIQUE NULLS NOT DISTINCT") })
        transaction(database) { plan.statements.forEach { exec(it) } }
        val second = PostgresMigrationGenerator.plan(database, schema, true)
        assertTrue(second.isEmpty, second.toString())
    }
}
