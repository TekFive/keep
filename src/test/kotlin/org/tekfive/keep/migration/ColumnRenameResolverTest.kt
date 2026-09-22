package org.tekfive.keep.migration

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.data.renamedFrom
import org.tekfive.keep.db.dbConnection
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ColumnRenameResolverTest {
    private val table = object : Table("rename_resolver.records") {
        val label = text("label").renamedFrom("name", "full_name", "public_name")
    }

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction { exec("CREATE SCHEMA rename_resolver") }
    }

    @AfterTest
    fun teardown() {
        transaction { exec("DROP SCHEMA rename_resolver CASCADE") }
    }

    @Test
    fun `each historical name resolves directly to the current name`(): Unit = transaction {
        for (previous in listOf("name", "full_name", "public_name")) {
            exec("CREATE TABLE rename_resolver.records ($previous TEXT)")
            val sql = resolveColumnRenames(dbConnection(), listOf(table)).single()
            assertTrue(sql.contains("RENAME COLUMN \"$previous\" TO \"label\""), sql)
            exec("DROP TABLE rename_resolver.records")
        }
    }

    @Test
    fun `missing table missing columns and already renamed column need no rename`(): Unit = transaction {
        assertEquals(emptyList(), resolveColumnRenames(dbConnection(), listOf(table)))
        exec("CREATE TABLE rename_resolver.records (id BIGINT)")
        assertEquals(emptyList(), resolveColumnRenames(dbConnection(), listOf(table)))
        exec("ALTER TABLE rename_resolver.records ADD COLUMN label TEXT")
        assertEquals(emptyList(), resolveColumnRenames(dbConnection(), listOf(table)))
    }

    @Test
    fun `multiple historical names and current plus historical names are ambiguous`(): Unit = transaction {
        exec("CREATE TABLE rename_resolver.records (name TEXT, full_name TEXT)")
        assertFailsWith<IllegalArgumentException> { resolveColumnRenames(dbConnection(), listOf(table)) }
        exec("ALTER TABLE rename_resolver.records RENAME COLUMN full_name TO label")
        assertFailsWith<IllegalArgumentException> { resolveColumnRenames(dbConnection(), listOf(table)) }
    }

    @Test
    fun `declarations cannot claim a current column or share a historical name`(): Unit = transaction {
        val shared = object : Table("rename_resolver.records") {
            val first = text("first").renamedFrom("previous")
            val second = text("second").renamedFrom("previous")
        }
        assertFailsWith<IllegalArgumentException> { resolveColumnRenames(dbConnection(), listOf(shared)) }
        val reused = object : Table("rename_resolver.records") {
            val first = text("first").renamedFrom("second")
            val second = text("second")
        }
        assertFailsWith<IllegalArgumentException> { resolveColumnRenames(dbConnection(), listOf(reused)) }
    }

    @Test
    fun `historical names are exact identifiers and safely quoted`(): Unit = transaction {
        val quoted = object : Table("rename_resolver.records") {
            val label = text("label").renamedFrom("Old \"Name", "Name")
        }
        exec("CREATE TABLE rename_resolver.records (\"Old \"\"Name\" TEXT, name TEXT)")
        val sql = resolveColumnRenames(dbConnection(), listOf(quoted)).single()
        assertTrue(sql.contains("RENAME COLUMN \"Old \"\"Name\" TO \"label\""), sql)
        exec(sql)
        assertEquals(emptyList(), resolveColumnRenames(dbConnection(), listOf(quoted)))
    }
}
