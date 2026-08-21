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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstantTrackedData(
    var label: String,
    override var updatedAt: Instant,
) : Data(), TrackUpdatedAtInstant

object InstantTrackedTable : DataTable<InstantTrackedData>("instant_tracked") {
    val label = varchar("label", 255)
    val updatedAt = column(InstantTrackedData::updatedAt)
}

class UuidInstantTrackedData(
    var label: String,
    override var updatedAt: Instant,
) : UuidData(), TrackUpdatedAtInstant

object UuidInstantTrackedTable : UuidDataTable<UuidInstantTrackedData>("uuid_instant_tracked") {
    val label = varchar("label", 255)
    val updatedAt = column(
        UuidInstantTrackedData::updatedAt,
        storage = InstantStorage.TIMESTAMP_WITH_TIME_ZONE,
    )
}

class TrackUpdatedAtTest {
    private lateinit var database: Database

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) {
            SchemaUtils.drop(UuidInstantTrackedTable, InstantTrackedTable)
            SchemaUtils.create(InstantTrackedTable, UuidInstantTrackedTable)
        }
    }

    @AfterTest
    fun teardown() {
        transaction(database) {
            SchemaUtils.drop(UuidInstantTrackedTable, InstantTrackedTable)
        }
    }

    @Test
    fun `Instant tracker updates bigint updated_at when long id data changes`() {
        transaction(database) {
            val data = InstantTrackedTable.create(InstantTrackedData("before", Instant.EPOCH))
            val earliestExpected = Instant.ofEpochMilli(System.currentTimeMillis())

            data.label = "after"
            InstantTrackedTable.update(data)

            val latestExpected = Instant.ofEpochMilli(System.currentTimeMillis())
            assertTrue(data.updatedAt >= earliestExpected)
            assertTrue(data.updatedAt <= latestExpected)
            assertEquals(
                data.updatedAt,
                InstantTrackedTable.selectAll()
                    .where { InstantTrackedTable.id eq data.id }
                    .single()[InstantTrackedTable.updatedAt],
            )
            assertFalse(data.isDirty)
        }
    }

    @Test
    fun `Instant tracker supports UUID data and preserves an explicitly supplied updated_at`() {
        transaction(database) {
            val data = UuidInstantTrackedTable.create(UuidInstantTrackedData("before", Instant.EPOCH))
            val earliestExpected = Instant.ofEpochMilli(System.currentTimeMillis())

            data.label = "after"
            UuidInstantTrackedTable.update(data, UuidInstantTrackedTable.label)

            val latestExpected = Instant.ofEpochMilli(System.currentTimeMillis())
            assertTrue(data.updatedAt >= earliestExpected)
            assertTrue(data.updatedAt <= latestExpected)

            val explicitUpdatedAt = Instant.parse("2026-08-20T12:34:56.123456Z")
            data.updatedAt = explicitUpdatedAt
            UuidInstantTrackedTable.update(data, UuidInstantTrackedTable.updatedAt)

            assertEquals(explicitUpdatedAt, data.updatedAt)
            assertEquals(
                explicitUpdatedAt,
                UuidInstantTrackedTable.selectAll()
                    .where { UuidInstantTrackedTable.id eq data.id }
                    .single()[UuidInstantTrackedTable.updatedAt],
            )
        }
    }
}
