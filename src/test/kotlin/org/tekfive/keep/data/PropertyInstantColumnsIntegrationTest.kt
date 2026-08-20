package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PropertyInstantModel(
    val bigintAt: Instant,
    val nativeAt: Instant,
    val optionalBigintAt: Instant?,
    val optionalNativeAt: Instant?,
) : UuidData()

object PropertyInstantTable : UuidDataTable<PropertyInstantModel>("property_instant_columns") {
    val bigintAt = column(PropertyInstantModel::bigintAt)
    val nativeAt = column(
        PropertyInstantModel::nativeAt,
        storage = InstantStorage.TIMESTAMP_WITH_TIME_ZONE,
    )
    val optionalBigintAt = column(PropertyInstantModel::optionalBigintAt)
    val optionalNativeAt = column(
        PropertyInstantModel::optionalNativeAt,
        storage = InstantStorage.TIMESTAMP_WITH_TIME_ZONE,
    )
}

class PropertyInstantColumnsIntegrationTest {
    private lateinit var database: Database

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) {
            SchemaUtils.drop(PropertyInstantTable)
            SchemaUtils.create(PropertyInstantTable)
        }
    }

    @AfterTest
    fun teardown() {
        transaction(database) {
            SchemaUtils.drop(PropertyInstantTable)
        }
    }

    @Test
    fun `round trips Instant values through bigint and native timestamp storage`() {
        val precise = Instant.parse("2026-08-20T12:34:56.123456Z")
        val millisecondPrecision = Instant.ofEpochMilli(precise.toEpochMilli())

        transaction(database) {
            PropertyInstantTable.create(PropertyInstantModel(precise, precise, null, null))
        }

        transaction(database) {
            val loaded = PropertyInstantTable.selectAll().single().let(PropertyInstantTable::map)
            assertEquals(millisecondPrecision, loaded.bigintAt)
            assertEquals(precise, loaded.nativeAt)
            assertNull(loaded.optionalBigintAt)
            assertNull(loaded.optionalNativeAt)

            assertEquals(
                millisecondPrecision,
                PropertyInstantTable.selectAll()
                    .where { PropertyInstantTable.bigintAt eq precise }
                    .single()[PropertyInstantTable.bigintAt],
            )
            assertEquals(
                precise,
                PropertyInstantTable.selectAll()
                    .where { PropertyInstantTable.nativeAt eq precise }
                    .single()[PropertyInstantTable.nativeAt],
            )
        }
    }
}
