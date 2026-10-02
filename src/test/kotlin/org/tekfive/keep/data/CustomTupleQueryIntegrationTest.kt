package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.db.db
import org.tekfive.keep.schema.view
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val QUERY_SCHEMA = "keep_custom_query_test"

interface CustomQueryRow {
    val label: String
    val visible: Boolean
}

class CustomQueryLongData(override val label: String, override val visible: Boolean) : Data(), CustomQueryRow
class CustomQueryUuidData(override val label: String, override val visible: Boolean) : UuidData(), CustomQueryRow

object CustomQueryAccess : Table("$QUERY_SCHEMA.access") {
    val label = text("label")
    val allowed = bool("allowed")
    override val primaryKey = PrimaryKey(label)
}

private fun joinedQuery(table: Table, label: Column<String>): Query {
    // All accessors, including pagination invoked outside db {}, must build the query in a transaction.
    checkNotNull(TransactionManager.currentOrNull())
    return table.join(CustomQueryAccess, JoinType.INNER, label, CustomQueryAccess.label)
        .select(table.columns).where { CustomQueryAccess.allowed eq true }
}

abstract class JoinedQueryLongTable<D : Data>(name: String) : DataTable<D>(name, "$QUERY_SCHEMA.record_ids") {
    val label = text("label")
    val visible = bool("visible")
    override fun createQuery(): Query = joinedQuery(this, label)
}

abstract class VisibleQueryLongTable<D : Data>(name: String) : JoinedQueryLongTable<D>(name) {
    override fun createQuery(): Query = super.createQuery().andWhere { visible eq true }
}

object CustomQueryLongTable : VisibleQueryLongTable<CustomQueryLongData>("$QUERY_SCHEMA.long_rows")

abstract class JoinedQueryUuidTable<D : UuidData>(name: String) : UuidDataTable<D>(name) {
    val label = text("label")
    val visible = bool("visible")
    override fun createQuery(): Query = joinedQuery(this, label)
}

abstract class VisibleQueryUuidTable<D : UuidData>(name: String) : JoinedQueryUuidTable<D>(name) {
    override fun createQuery(): Query = super.createQuery().andWhere { visible eq true }
}

object CustomQueryUuidTable : VisibleQueryUuidTable<CustomQueryUuidData>("$QUERY_SCHEMA.uuid_rows")

object CustomQueryLongTuple : DataTuple<CustomQueryLongData>("$QUERY_SCHEMA.long_rows", setOf("id")) {
    override val id = long("id")
    val label = text("label")
    val visible = bool("visible")
    override fun createQuery(): Query = joinedQuery(this, label).andWhere { visible eq true }
}

object CustomQueryUuidTuple : UuidDataTuple<CustomQueryUuidData>("$QUERY_SCHEMA.uuid_rows", setOf("id")) {
    override val id = javaUUID("id")
    val label = text("label")
    val visible = bool("visible")
    override fun createQuery(): Query = joinedQuery(this, label).andWhere { visible eq true }
}

abstract class VisibleQueryLongView<D : Data>(name: String) : DataView<D>(name) {
    val label = text("label")
    val visible = bool("visible")
    override fun createQuery(): Query = joinedQuery(this, label).andWhere { visible eq true }
}

object CustomQueryLongView : VisibleQueryLongView<CustomQueryLongData>("$QUERY_SCHEMA.long_view") {
    override val viewDefinition = view(CustomQueryLongTable) { CustomQueryLongTable.selectAll() }
}

abstract class VisibleQueryUuidView<D : UuidData>(name: String) : UuidDataView<D>(name) {
    val label = text("label")
    val visible = bool("visible")
    override fun createQuery(): Query = joinedQuery(this, label).andWhere { visible eq true }
}

object CustomQueryUuidView : VisibleQueryUuidView<CustomQueryUuidData>("$QUERY_SCHEMA.uuid_view") {
    override val viewDefinition = view(CustomQueryUuidTable) { CustomQueryUuidTable.selectAll() }
}

class CustomTupleQueryIntegrationTest {
    private val longIds = listOf(1L, 2L, 3L, 4L)
    private val uuidIds = longIds.map { UUID(0, it) }

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction {
            exec("CREATE SCHEMA $QUERY_SCHEMA")
            SchemaUtils.create(CustomQueryAccess, CustomQueryLongTable, CustomQueryUuidTable)
            exec("INSERT INTO $QUERY_SCHEMA.access VALUES ('alpha', true), ('beta', true), ('hidden', true), ('blocked', false)")
            exec("INSERT INTO $QUERY_SCHEMA.long_rows VALUES (1, 'alpha', true), (2, 'beta', true), (3, 'hidden', false), (4, 'blocked', true)")
            val labels = listOf("alpha", "beta", "hidden", "blocked")
            uuidIds.forEachIndexed { index, id ->
                exec("INSERT INTO $QUERY_SCHEMA.uuid_rows VALUES ('$id', '${labels[index]}', ${index != 2})")
            }
            exec("CREATE VIEW $QUERY_SCHEMA.long_view AS ${CustomQueryLongView.viewDefinition.renderQuery()}")
            exec("CREATE VIEW $QUERY_SCHEMA.uuid_view AS ${CustomQueryUuidView.viewDefinition.renderQuery()}")
        }
    }

    @AfterTest
    fun cleanup() {
        transaction { exec("DROP SCHEMA IF EXISTS $QUERY_SCHEMA CASCADE") }
    }

    @Test
    fun `Long tables inherit and compose custom queries across intermediate classes`() =
        verifyQueries(CustomQueryLongTable, CustomQueryLongTable.label, longIds)

    @Test
    fun `UUID tables inherit and compose custom queries across intermediate classes`() =
        verifyQueries(CustomQueryUuidTable, CustomQueryUuidTable.label, uuidIds)

    @Test
    fun `Long tuples use custom queries without a primary key or linked data`() =
        verifyQueries(CustomQueryLongTuple, CustomQueryLongTuple.label, longIds)

    @Test
    fun `UUID tuples use custom queries without a primary key or linked data`() =
        verifyQueries(CustomQueryUuidTuple, CustomQueryUuidTuple.label, uuidIds)

    @Test
    fun `Long views inherit custom queries without a primary key or linked data`() =
        verifyQueries(CustomQueryLongView, CustomQueryLongView.label, longIds)

    @Test
    fun `UUID views inherit custom queries without a primary key or linked data`() =
        verifyQueries(CustomQueryUuidView, CustomQueryUuidView.label, uuidIds)

    private fun <ID : Any, D> verifyQueries(table: TypedDataTuple<ID, D>, label: Column<String>, ids: List<ID>)
        where D : IdentifiedData<ID>, D : CustomQueryRow {
        // Exercise every query path without an outer transaction (and without sharing cached results).
        assertEquals("alpha", table.getById(ids[0]).label)
        assertEquals("beta", table.findById(ids[1])?.label)
        for (id in ids.drop(2)) {
            assertNull(table.findById(id))
            assertFailsWith<NoSuchElementException> { table.getById(id) }
        }
        assertNull(table.findById(null))
        assertEquals(emptyList(), table.findByIds(emptyList()))
        assertEquals(listOf("beta", "alpha", "beta"), table.findByIds(listOf(ids[1], ids[2], ids[0], ids[3], ids[1])).map { it.label })
        assertEquals("alpha", table.findByUnique("alpha", label)?.label)
        assertEquals("beta", table.findByUnique(label eq "beta")?.label)
        for (excluded in listOf("hidden", "blocked")) {
            assertNull(table.findByUnique(excluded, label))
            assertNull(table.findByUnique(label eq excluded))
            assertEquals(emptyList(), table.findWhere(label eq excluded))
            assertEquals(0, table.count(label eq excluded))
            assertFalse(table.rowExists(label eq excluded))
        }
        assertEquals(listOf("alpha", "beta"), table.findAll(label to SortOrder.ASC).map { it.label })
        assertEquals(listOf("beta", "alpha"), table.findWhere(Op.TRUE, label to SortOrder.DESC).map { it.label })
        assertEquals(2, table.count(Op.TRUE))
        assertTrue(table.rowExists(label eq "alpha"))
        val page = table.findPaged(Op.TRUE, page = 2, size = 1, label to SortOrder.ASC)
        assertEquals(2, page.total)
        assertEquals(listOf("beta"), page.data.map { it.label })
        // Pagination and predicates must never carry over into later base queries.
        assertEquals(2, table.findAll().size)

        db(cache = true) {
            val alpha = assertNotNull(table.findById(ids[0]))
            assertSame(alpha, table.getById(ids[0]))
            assertNull(table.findById(ids[2]))
            val mixed = table.findByIds(listOf(ids[1], ids[0], ids[2], ids[3], ids[1]))
            assertEquals(listOf("beta", "alpha", "beta"), mixed.map { it.label })
            assertSame(alpha, mixed[1])
            assertSame(mixed[0], table.findById(ids[1]))
            assertNull(table.findById(ids[3]))
        }
    }

    @Test
    fun `row and whole-table caches load through the custom query`() {
        val rowCache = object : DatabaseTupleCache<CustomQueryLongData>(CustomQueryLongTable) {
            override val maxCachedRows = 10
            override val maxCacheSeconds = 300
        }
        val tableCache = object : DatabaseTableCache<CustomQueryLongData>(CustomQueryLongTable) {
            override val maxCacheSeconds = 300
        }
        assertEquals("alpha", rowCache.find(1L)?.label)
        assertNull(rowCache.find(3L))
        assertNull(rowCache.find(4L))
        assertEquals(listOf("alpha", "beta"), tableCache.findAll().map { it.label })
    }
}
