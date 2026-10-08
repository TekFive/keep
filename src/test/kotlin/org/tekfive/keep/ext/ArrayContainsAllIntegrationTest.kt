package org.tekfive.keep.ext

import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.tekfive.keep.array.containsAll
import org.tekfive.keep.data.Data
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.data.column
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContainsAllUuidData(
    val label: String,
    val members: Set<UUID>,
    val memberList: List<UUID>,
    val optionalMembers: Set<UUID>?,
    val optionalList: List<UUID>?,
) : Data()

object ContainsAllUuidTable : DataTable<ContainsAllUuidData>("contains_all_uuids", "contains_all_uuid_ids") {
    val label = column(ContainsAllUuidData::label)
    val members = column(ContainsAllUuidData::members, name = "member_ids")
    val memberList = column(ContainsAllUuidData::memberList)
    val optionalMembers = column(ContainsAllUuidData::optionalMembers)
    val optionalList = column(ContainsAllUuidData::optionalList)
}

class ArrayContainsAllIntegrationTest {
    private lateinit var database: Database
    private val a = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val b = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val c = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
    private val missing = UUID.fromString("00000000-0000-0000-0000-000000000004")

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) { SchemaUtils.create(ContainsAllUuidTable) }
    }

    @AfterTest
    fun cleanup() {
        transaction(database) { SchemaUtils.drop(ContainsAllUuidTable) }
    }

    @Test
    fun `rowExists requires every UUID while allowing additional stored members`() {
        assertFalse(ContainsAllUuidTable.rowExists(ContainsAllUuidTable.members containsAll emptySet()))
        transaction(database) {
            ContainsAllUuidTable.create(ContainsAllUuidData("filled", setOf(a, b, c), listOf(c, a, a, b), setOf(a, c), listOf(c, a)))
        }
        with(ContainsAllUuidTable) {
            assertTrue(rowExists(members containsAll setOf(c, a)))
            assertTrue(rowExists(members containsAll setOf(a, b, c)))
            assertFalse(rowExists(members containsAll setOf(a, missing)))
            assertFalse(rowExists(members containsAll setOf(missing)))
            assertTrue(rowExists(optionalMembers containsAll setOf(a, c)))
            assertFalse(rowExists(optionalMembers containsAll setOf(b)))
            assertTrue(rowExists((members containsAll setOf(a)) and (label eq "filled")))
            assertFalse(rowExists((members containsAll setOf(a)) and (label eq "other")))
            assertTrue(rowExists((members containsAll setOf(missing)) or (label eq "filled")))
        }
    }

    @Test
    fun `lists and sets ignore element order and duplicate counts`() = transaction(database) {
        with(ContainsAllUuidTable) {
            create(ContainsAllUuidData("filled", setOf(a, b, c), listOf(c, a, a, b), setOf(a), listOf(a)))
            assertTrue(rowExists(memberList containsAll setOf(b, c, a)))
            assertTrue(rowExists(memberList containsAll listOf(b, b, b)))
            assertTrue(rowExists(members containsAll listOf(c, c, a)))
            assertTrue(rowExists(optionalMembers containsAll listOf(a, a)))
            assertTrue(rowExists(optionalList containsAll listOf(a, a)))
            assertFalse(rowExists(optionalList containsAll listOf(a, missing)))
            val mapped = selectAll().single().let(::map)
            assertEquals(setOf(a, b, c), mapped.members)
        }
    }

    @Test
    fun `empty input matches non-null arrays including empty arrays but never SQL null`() = transaction(database) {
        with(ContainsAllUuidTable) {
            create(ContainsAllUuidData("filled", setOf(a), listOf(a), setOf(a), listOf(a)))
            create(ContainsAllUuidData("empty", emptySet(), emptyList(), null, null))
            create(ContainsAllUuidData("null", emptySet(), emptyList(), null, null))
            // KEEP Data inserts map empty nullable collections to SQL NULL. Use a direct update
            // here to exercise a genuinely empty, non-null PostgreSQL array in nullable columns.
            update({ label eq "empty" }) {
                it[optionalMembers] = emptySet()
                it[optionalList] = emptyList()
            }
            assertEquals(3, selectAll().where { members containsAll emptySet() }.count())
            assertEquals(3, selectAll().where { memberList containsAll emptyList() }.count())
            assertEquals(setOf("filled", "empty"), selectAll().where { optionalMembers containsAll emptySet() }.map { it[label] }.toSet())
            assertEquals(setOf("filled", "empty"), selectAll().where { optionalList containsAll emptyList() }.map { it[label] }.toSet())
            assertEquals(listOf("filled"), selectAll().where { optionalMembers containsAll setOf(a) }.map { it[label] })
            assertFalse(rowExists((label eq "empty") and (members containsAll setOf(a))))
        }
    }

    @Test
    fun `predicate binds a copied UUID array instead of interpolating UUID literals`() = transaction(database) {
        val supplied = mutableSetOf(a, c)
        val predicate = ContainsAllUuidTable.members containsAll supplied
        supplied.clear()
        val builder = QueryBuilder(true)
        predicate.toQueryBuilder(builder)
        assertTrue(builder.toString().contains(" @> ?"))
        assertFalse(builder.toString().contains(a.toString()))
        val (type, value) = builder.args.single()
        val arrayType = assertIs<ArrayColumnType<*, *>>(type)
        assertIs<UUIDColumnType>(arrayType.delegate)
        assertEquals(listOf(a, c), value)
    }
}
