package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

private const val CACHE_SCHEMA = "keep_table_cache_test"

class TableCacheRow(val label: String, val active: Boolean) : Data()
class UuidTableCacheRow(val label: String, val active: Boolean) : UuidData()

object TableCacheRecords : DataTable<TableCacheRow>("$CACHE_SCHEMA.records", "$CACHE_SCHEMA.record_ids") {
    val label = column(TableCacheRow::label)
    val active = column(TableCacheRow::active)
}
object UuidTableCacheRecords : UuidDataTable<UuidTableCacheRow>("$CACHE_SCHEMA.uuid_records") {
    val label = column(UuidTableCacheRow::label)
    val active = column(UuidTableCacheRow::active)
}

class DatabaseTableCacheIntegrationTest {
    private lateinit var database: Database

    @BeforeTest fun setup() {
        database = TestDatabase.connect()
        transaction(database) {
            exec("CREATE SCHEMA $CACHE_SCHEMA")
            SchemaUtils.create(TableCacheRecords, UuidTableCacheRecords)
        }
    }

    @AfterTest fun cleanup() {
        transaction(database) { exec("DROP SCHEMA IF EXISTS $CACHE_SCHEMA CASCADE") }
    }

    @Test fun `Long table snapshot stays stable across transactions until explicitly refreshed`() {
        val cache = object : DatabaseTableCache<TableCacheRow>(TableCacheRecords) {
            override val maxCacheSeconds = 300
            var loads = 0
            override fun fetchFromDb(): List<TableCacheRow> { loads++; return super.fetchFromDb() }
        }
        transaction(database) {
            TableCacheRecords.insert { it[id] = 2; it[label] = "second"; it[active] = true }
            TableCacheRecords.insert { it[id] = 1; it[label] = "first"; it[active] = false }
        }
        val before = cache.findAll()
        assertEquals(listOf(1L, 2L), before.map { it.id })
        assertSame(before[0], cache[1])
        assertNull(cache.find(3))
        transaction(database) {
            exec("UPDATE $CACHE_SCHEMA.records SET label = 'updated' WHERE id = 1")
            exec("DELETE FROM $CACHE_SCHEMA.records WHERE id = 2")
            exec("INSERT INTO $CACHE_SCHEMA.records VALUES (3, 'third', TRUE)")
        }
        assertEquals(listOf("first", "second"), cache.findAll().map { it.label })
        assertNull(cache.find(3))
        assertEquals(1, cache.loads)

        val refreshed = cache.refresh()
        assertEquals(listOf(1L, 3L), refreshed.map { it.id })
        assertEquals("updated", cache[1].label)
        assertNull(cache.find(2))
        assertEquals("third", cache[3].label)
        assertEquals(listOf("first", "second"), before.map { it.label })
        assertEquals(2, cache.loads)
    }

    @Test fun `UUID table snapshot loads the complete predicate selection and refreshes membership`() {
        val cache = object : UuidDatabaseTableCache<UuidTableCacheRow>(UuidTableCacheRecords, UuidTableCacheRecords.active eq true) {
            override val maxCacheSeconds = 300
            var loads = 0
            override fun fetchFromDb(): List<UuidTableCacheRow> { loads++; return super.fetchFromDb() }
        }
        val first = UUID(0, 1)
        val second = UUID(0, 2)
        transaction(database) {
            UuidTableCacheRecords.insert { it[id] = second; it[label] = "inactive"; it[active] = false }
            UuidTableCacheRecords.insert { it[id] = first; it[label] = "active"; it[active] = true }
        }
        assertEquals(listOf(first), cache.findAll().map { it.id })
        assertSame(cache.findAll().single(), cache[first])
        assertNull(cache.find(second))
        assertEquals(1, cache.loads)
        transaction(database) {
            exec("UPDATE $CACHE_SCHEMA.uuid_records SET active = NOT active")
        }
        assertEquals(listOf(first), cache.findAll().map { it.id })
        cache.invalidate()
        assertEquals(listOf(second), cache.findAll().map { it.id })
        assertNull(cache.find(first))
        assertEquals("inactive", cache[second].label)
        assertEquals(2, cache.loads)
    }
}
