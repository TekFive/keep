package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PropertyUuidListData(var relatedIds: List<UUID>, var optionalIds: List<UUID>?) : Data()
object PropertyUuidListTable : DataTable<PropertyUuidListData>("property_uuid_lists", "property_uuid_list_ids") {
    val requiredColumn = column(PropertyUuidListData::relatedIds)
    val optionalColumn = column(PropertyUuidListData::optionalIds, name = "other_ids")
}

class PropertyUuidListUuidData(var relatedIds: List<UUID>, var optionalIds: List<UUID>?) : UuidData()
object PropertyUuidListUuidTable : UuidDataTable<PropertyUuidListUuidData>("property_uuid_lists_uuid") {
    val requiredColumn = column(PropertyUuidListUuidData::relatedIds, name = "stored_ids")
    val optionalColumn = column(PropertyUuidListUuidData::optionalIds)
}

class PropertyUuidListColumnsIntegrationTest {
    private lateinit var database: Database
    private val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val second = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
    private val ids = listOf(second, first, second)

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) { SchemaUtils.create(PropertyUuidListTable, PropertyUuidListUuidTable) }
    }

    @AfterTest
    fun cleanup() {
        transaction(database) { SchemaUtils.drop(PropertyUuidListUuidTable, PropertyUuidListTable) }
    }

    @Test
    fun `Long tables round trip UUID arrays preserve order and duplicates and bind equality queries`() {
        transaction(database) {
            PropertyUuidListTable.create(PropertyUuidListData(ids, ids))
            exec("SELECT pg_typeof(related_ids)::text, pg_typeof(other_ids)::text FROM property_uuid_lists") { result ->
                check(result.next())
                assertEquals("uuid[]", result.getString(1))
                assertEquals("uuid[]", result.getString(2))
            }
        }
        transaction(database) {
            val loaded = PropertyUuidListTable.selectAll()
                .where { PropertyUuidListTable.requiredColumn eq ids }
                .single().let(PropertyUuidListTable::map)
            assertEquals(ids, loaded.relatedIds)
            assertEquals(ids, loaded.optionalIds)
            loaded.relatedIds = emptyList()
            loaded.optionalIds = null
            PropertyUuidListTable.save(loaded)
        }
        transaction(database) {
            val loaded = PropertyUuidListTable.selectAll()
                .where { PropertyUuidListTable.requiredColumn eq emptyList() }
                .single().let(PropertyUuidListTable::map)
            assertEquals(emptyList(), loaded.relatedIds)
            assertNull(loaded.optionalIds)
        }
    }

    @Test
    fun `UUID tables round trip empty null and populated property-bound lists`() {
        transaction(database) {
            PropertyUuidListUuidTable.create(PropertyUuidListUuidData(emptyList(), null))
        }
        transaction(database) {
            val loaded = PropertyUuidListUuidTable.selectAll().single().let(PropertyUuidListUuidTable::map)
            assertEquals(emptyList(), loaded.relatedIds)
            assertNull(loaded.optionalIds)
            loaded.relatedIds = ids
            loaded.optionalIds = ids.reversed()
            PropertyUuidListUuidTable.save(loaded)
        }
        transaction(database) {
            val loaded = PropertyUuidListUuidTable.selectAll()
                .where { PropertyUuidListUuidTable.optionalColumn eq ids.reversed() }
                .single().let(PropertyUuidListUuidTable::map)
            assertEquals(ids, loaded.relatedIds)
            assertEquals(ids.reversed(), loaded.optionalIds)
            // Preserve KEEP's existing normalization of empty nullable collections to SQL NULL.
            loaded.optionalIds = emptyList()
            PropertyUuidListUuidTable.save(loaded)
        }
        transaction(database) {
            assertNull(PropertyUuidListUuidTable.selectAll().single()[PropertyUuidListUuidTable.optionalColumn])
        }
    }
}
