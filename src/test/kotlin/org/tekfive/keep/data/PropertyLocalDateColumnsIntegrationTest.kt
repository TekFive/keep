package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PropertyLocalDateData(var birthDate: LocalDate, var optionalDate: LocalDate?) : Data()

object PropertyLocalDateTable : DataTable<PropertyLocalDateData>(
    "property_local_date_columns",
    idSequenceName = "property_local_date_ids",
) {
    val requiredColumn = column(PropertyLocalDateData::birthDate)
    val optionalColumn = column(PropertyLocalDateData::optionalDate, name = "other_date")
}

class PropertyLocalDateUuidData(var birthDate: LocalDate, var optionalDate: LocalDate?) : UuidData()

object PropertyLocalDateUuidTable : UuidDataTable<PropertyLocalDateUuidData>("property_local_date_uuid_columns") {
    val requiredColumn = column(PropertyLocalDateUuidData::birthDate, name = "date_of_birth")
    val optionalColumn = column(PropertyLocalDateUuidData::optionalDate)
}

class PropertyLocalDateColumnsIntegrationTest {
    private lateinit var database: Database
    private val leapDay = LocalDate.of(2024, 2, 29)
    private val beforeEpoch = LocalDate.of(1960, 1, 2)

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) {
            SchemaUtils.create(PropertyLocalDateTable, PropertyLocalDateUuidTable)
        }
    }

    @AfterTest
    fun teardown() {
        transaction(database) {
            SchemaUtils.drop(PropertyLocalDateUuidTable, PropertyLocalDateTable)
        }
    }

    @Test
    fun `DataTable round trips queries and updates property-bound dates`() {
        transaction(database) {
            PropertyLocalDateTable.create(PropertyLocalDateData(leapDay, null))
        }
        transaction(database) {
            val loaded = PropertyLocalDateTable.selectAll()
                .where { PropertyLocalDateTable.requiredColumn eq leapDay }
                .single().let(PropertyLocalDateTable::map)
            assertEquals(leapDay, loaded.birthDate)
            assertNull(loaded.optionalDate)
            loaded.birthDate = beforeEpoch
            loaded.optionalDate = leapDay
            PropertyLocalDateTable.save(loaded)
        }
        transaction(database) {
            val loaded = PropertyLocalDateTable.selectAll()
                .where { PropertyLocalDateTable.optionalColumn eq leapDay }
                .single().let(PropertyLocalDateTable::map)
            assertEquals(beforeEpoch, loaded.birthDate)
            assertEquals(leapDay, loaded.optionalDate)
        }
    }

    @Test
    fun `UuidDataTable round trips queries and clears property-bound dates`() {
        transaction(database) {
            PropertyLocalDateUuidTable.create(PropertyLocalDateUuidData(beforeEpoch, leapDay))
        }
        transaction(database) {
            val loaded = PropertyLocalDateUuidTable.selectAll()
                .where { PropertyLocalDateUuidTable.optionalColumn eq leapDay }
                .single().let(PropertyLocalDateUuidTable::map)
            assertEquals(beforeEpoch, loaded.birthDate)
            assertEquals(leapDay, loaded.optionalDate)
            loaded.birthDate = leapDay
            loaded.optionalDate = null
            PropertyLocalDateUuidTable.save(loaded)
        }
        transaction(database) {
            val loaded = PropertyLocalDateUuidTable.selectAll()
                .where { PropertyLocalDateUuidTable.requiredColumn eq leapDay }
                .single().let(PropertyLocalDateUuidTable::map)
            assertEquals(leapDay, loaded.birthDate)
            assertNull(loaded.optionalDate)
        }
    }
}
