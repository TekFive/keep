package org.tekfive.keep.ext

import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.array.containsAll
import org.tekfive.keep.array.includes
import org.tekfive.keep.array.intersects
import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.data.column
import org.tekfive.keep.data.dataEnumList
import org.tekfive.keep.data.dataEnumSet
import org.tekfive.keep.data.enumIntersects
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private enum class ArrayStatus(override val id: Int) : DataEnum {
    ACTIVE(10), PENDING(42), CLOSED(-7), MISSING(1000),
}

private class EnumArrayProperties(val statuses: List<ArrayStatus>, val optionalStatuses: Set<ArrayStatus>?)

private object EnumArrayTable : Table("enum_array_operators") {
    val label = text("label")
    val statuses = column(EnumArrayProperties::statuses)
    val statusSet = dataEnumSet<ArrayStatus>("status_set")
    val optionalList = dataEnumList<ArrayStatus>("optional_list").nullable()
    val optionalSet = column(EnumArrayProperties::optionalStatuses)
}

class DataEnumArrayOperatorsIntegrationTest {
    private lateinit var database: Database
    private val a = ArrayStatus.ACTIVE
    private val b = ArrayStatus.PENDING
    private val c = ArrayStatus.CLOSED
    private val missing = ArrayStatus.MISSING
    private val columns: List<ExpressionWithColumnType<out Collection<ArrayStatus>?>> = listOf(
        EnumArrayTable.statuses, EnumArrayTable.statusSet, EnumArrayTable.optionalList, EnumArrayTable.optionalSet,
    )

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) {
            SchemaUtils.create(EnumArrayTable)
            for (name in listOf("filled", "empty", "null")) {
                EnumArrayTable.insert {
                    it[label] = name
                    it[statuses] = if (name == "filled") listOf(c, a, a, b) else emptyList()
                    it[statusSet] = if (name == "filled") setOf(c, a, b) else emptySet()
                    it[optionalList] = when (name) {
                        "filled" -> listOf(c, a, a, b)
                        "empty" -> emptyList()
                        else -> null
                    }
                    it[optionalSet] = when (name) {
                        "filled" -> setOf(c, a, b)
                        "empty" -> emptySet()
                        else -> null
                    }
                }
            }
        }
    }

    @AfterTest
    fun cleanup() {
        transaction(database) { SchemaUtils.drop(EnumArrayTable) }
    }

    @Test
    fun `includes matches stable enum IDs on lists sets and nullable arrays`() = transaction(database) {
        for (column in columns) {
            assertEquals(listOf("filled"), EnumArrayTable.selectAll().where { column includes c }.map { it[EnumArrayTable.label] })
            assertEquals(0, EnumArrayTable.selectAll().where { column includes missing }.count())
            val builder = QueryBuilder(true)
            (column includes c).toQueryBuilder(builder)
            assertTrue(builder.toString().startsWith("? = ANY("))
            val (type, value) = builder.args.single()
            assertIs<IntegerColumnType>(type)
            assertEquals(-7, value)
        }
    }

    @Test
    fun `intersects matches any enum and preserves enumIntersects behavior`() = transaction(database) {
        for (column in columns) {
            val supplied = mutableListOf(c, missing)
            val predicate = column intersects supplied
            supplied.clear()
            assertEquals(listOf("filled"), EnumArrayTable.selectAll().where { predicate }.map { it[EnumArrayTable.label] })
            assertEquals(0, EnumArrayTable.selectAll().where { column intersects setOf(missing) }.count())
            assertEquals(0, EnumArrayTable.selectAll().where { column intersects emptyList() }.count())
            val builder = QueryBuilder(false)
            predicate.toQueryBuilder(builder)
            assertTrue(builder.toString().endsWith(" && ARRAY[-7,1000]::integer[]"))
            val aliasBuilder = QueryBuilder(false)
            (column enumIntersects listOf(c, missing)).toQueryBuilder(aliasBuilder)
            assertEquals(builder.toString(), aliasBuilder.toString())
        }
    }

    @Test
    fun `containsAll allows extra stored values and ignores order and duplicates`() = transaction(database) {
        for (column in columns) {
            for (required in listOf(setOf(a, c), listOf(b, b), listOf(b, a, c))) {
                assertEquals(listOf("filled"), EnumArrayTable.selectAll().where { column containsAll required }.map { it[EnumArrayTable.label] })
            }
            assertEquals(0, EnumArrayTable.selectAll().where { column containsAll listOf(a, missing) }.count())
        }
    }

    @Test
    fun `empty containment includes empty arrays but excludes SQL null`() = transaction(database) {
        for (column in columns) {
            val expected = if (column.columnType.nullable) setOf("filled", "empty") else setOf("filled", "empty", "null")
            assertEquals(expected, EnumArrayTable.selectAll().where { column containsAll emptySet() }.map { it[EnumArrayTable.label] }.toSet())
        }
    }

    @Test
    fun `containsAll binds a copied integer array of enum IDs`() = transaction(database) {
        for (column in columns) {
            val supplied = mutableSetOf(a, c)
            val predicate = column containsAll supplied
            supplied.clear()
            val builder = QueryBuilder(true)
            predicate.toQueryBuilder(builder)
            assertTrue(builder.toString().endsWith(" @> ?"))
            val (type, value) = builder.args.single()
            assertIs<IntegerColumnType>(assertIs<ArrayColumnType<*, *>>(type).delegate)
            assertEquals(listOf(10, -7), value)
            assertEquals(1, EnumArrayTable.selectAll().where { predicate }.count())
        }
    }

    @Test
    fun `enum predicates compose with other query conditions`() = transaction(database) {
        with(EnumArrayTable) {
            assertTrue(selectAll().where { (statuses includes a) and (statusSet containsAll setOf(c)) }.any())
            assertFalse(selectAll().where { (statuses includes a) and (label eq "empty") }.any())
            assertTrue(selectAll().where { (optionalList intersects setOf(missing)) or (optionalSet containsAll listOf(c)) }.any())
        }
    }
}
