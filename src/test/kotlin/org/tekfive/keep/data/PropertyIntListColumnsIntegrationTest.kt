package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.array.includes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PropertyIntListData(var scores: List<Int>, var optionalScores: List<Int>?) : Data()
object PropertyIntListTable : DataTable<PropertyIntListData>("property_int_lists", "property_int_list_ids") {
    val requiredColumn = column(PropertyIntListData::scores)
    val optionalColumn = column(PropertyIntListData::optionalScores, name = "other_scores")
}

class PropertyIntListUuidData(var scores: List<Int>, var optionalScores: List<Int>?) : UuidData()
object PropertyIntListUuidTable : UuidDataTable<PropertyIntListUuidData>("property_int_lists_uuid") {
    val requiredColumn = column(PropertyIntListUuidData::scores, name = "stored_scores")
    val optionalColumn = column(PropertyIntListUuidData::optionalScores)
}

class PropertyIntListColumnsIntegrationTest {
    private lateinit var database: Database
    private val scores = listOf(Int.MAX_VALUE, -1, 0, Int.MIN_VALUE, -1)

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) { SchemaUtils.create(PropertyIntListTable, PropertyIntListUuidTable) }
    }

    @AfterTest
    fun cleanup() {
        transaction(database) { SchemaUtils.drop(PropertyIntListUuidTable, PropertyIntListTable) }
    }

    @Test
    fun `Long tables round trip integer arrays preserve boundaries order and duplicates and support queries`() {
        transaction(database) {
            PropertyIntListTable.create(PropertyIntListData(scores, scores))
            exec("SELECT pg_typeof(scores)::text, pg_typeof(other_scores)::text FROM property_int_lists") { result ->
                check(result.next())
                assertEquals("integer[]", result.getString(1))
                assertEquals("integer[]", result.getString(2))
            }
        }
        transaction(database) {
            val loaded = PropertyIntListTable.selectAll()
                .where { PropertyIntListTable.requiredColumn eq scores }
                .single().let(PropertyIntListTable::map)
            assertEquals(scores, loaded.scores)
            assertEquals(scores, loaded.optionalScores)
            assertEquals(1, PropertyIntListTable.selectAll().where { PropertyIntListTable.requiredColumn includes Int.MIN_VALUE }.count())
            assertEquals(1, PropertyIntListTable.selectAll().where { PropertyIntListTable.optionalColumn includes -1 }.count())
            assertEquals(0, PropertyIntListTable.selectAll().where { PropertyIntListTable.requiredColumn includes 42 }.count())
            loaded.scores = emptyList()
            loaded.optionalScores = null
            PropertyIntListTable.save(loaded)
        }
        transaction(database) {
            val loaded = PropertyIntListTable.selectAll()
                .where { PropertyIntListTable.requiredColumn eq emptyList() }
                .single().let(PropertyIntListTable::map)
            assertEquals(emptyList(), loaded.scores)
            assertNull(loaded.optionalScores)
        }
    }

    @Test
    fun `UUID tables round trip empty null and populated property-bound integer lists`() {
        transaction(database) {
            PropertyIntListUuidTable.create(PropertyIntListUuidData(emptyList(), null))
        }
        transaction(database) {
            val loaded = PropertyIntListUuidTable.selectAll().single().let(PropertyIntListUuidTable::map)
            assertEquals(emptyList(), loaded.scores)
            assertNull(loaded.optionalScores)
            loaded.scores = scores
            loaded.optionalScores = scores.reversed()
            PropertyIntListUuidTable.save(loaded)
        }
        transaction(database) {
            val loaded = PropertyIntListUuidTable.selectAll()
                .where { PropertyIntListUuidTable.optionalColumn eq scores.reversed() }
                .single().let(PropertyIntListUuidTable::map)
            assertEquals(scores, loaded.scores)
            assertEquals(scores.reversed(), loaded.optionalScores)
            // Preserve KEEP's existing normalization of empty nullable collections to SQL NULL.
            loaded.optionalScores = emptyList()
            PropertyIntListUuidTable.save(loaded)
        }
        transaction(database) {
            assertNull(PropertyIntListUuidTable.selectAll().single()[PropertyIntListUuidTable.optionalColumn])
        }
    }
}
