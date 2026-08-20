package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.JsonValue
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.json
import org.tekfive.keep.schema.PostgresTargetVersion
import org.tekfive.keep.schema.withOfflinePostgresDdlContext
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private enum class PropertyColumnStatus(override val id: Int) : DataEnum {
    ACTIVE(1),
    INACTIVE(2),
}

private data class PropertyColumnPayload(val value: String) : ToJsonObject {
    override fun toJsonObject(): JsonObject = json { "value" set value }

    companion object : FromJsonObject<PropertyColumnPayload> {
        override fun fromJson(json: JsonObject): PropertyColumnPayload =
            PropertyColumnPayload(json.string("value") ?: "")
    }
}

@Suppress("unused")
private class PropertyColumnModel(
    val displayName: String,
    val optionalLabel: String?,
    val tinyValue: Byte,
    val shortValue: Short?,
    val itemCount: Int,
    val createdAt: Long,
    val updatedAt: Long?,
    val optionalLong: Long?,
    val occurredAt: Instant,
    val optionalOccurredAt: Instant?,
    val nativeOccurredAt: Instant,
    val optionalNativeOccurredAt: Instant?,
    val ratio: Float,
    val score: Double?,
    val enabled: Boolean,
    val amount: BigDecimal?,
    val publicId: UUID,
    val optionalId: UUID?,
    val content: ByteArray,
    val jsonValue: JsonValue,
    val jsonObject: JsonObject?,
    val jsonArray: JsonArray,
    val status: PropertyColumnStatus,
    val optionalStatus: PropertyColumnStatus?,
    val tags: List<String>,
    val optionalTags: List<String>?,
    val statuses: List<PropertyColumnStatus>,
    val optionalStatuses: List<PropertyColumnStatus>?,
    val statusIds: List<PropertyColumnStatus>,
    val statusSet: Set<PropertyColumnStatus>,
    val optionalStatusSet: Set<PropertyColumnStatus>?,
    val numbers: Set<Long>,
    val optionalNumbers: Set<Long>?,
    val payload: PropertyColumnPayload,
    val optionalPayload: PropertyColumnPayload?,
    val payloads: List<PropertyColumnPayload>,
    val optionalPayloads: List<PropertyColumnPayload>?,
    val parentId: Long,
    val optionalParentId: Long?,
)

private object PropertyColumnParents : Table("property_column_parents") {
    val id = long("id")
}

private object PropertyColumnTable : Table("property_columns") {
    val displayName = column(PropertyColumnModel::displayName, maxSize = 120)
    val optionalLabel = column(PropertyColumnModel::optionalLabel)
    val tinyValue = column(PropertyColumnModel::tinyValue)
    val shortValue = column(PropertyColumnModel::shortValue)
    val itemCount = column(PropertyColumnModel::itemCount, name = "items")
    val createdAt = timestamp(PropertyColumnModel::createdAt)
    val updatedAt = timestamp(PropertyColumnModel::updatedAt)
    val optionalLong = column(PropertyColumnModel::optionalLong)
    val occurredAt = column(PropertyColumnModel::occurredAt)
    val optionalOccurredAt = column(PropertyColumnModel::optionalOccurredAt)
    val nativeOccurredAt = column(
        PropertyColumnModel::nativeOccurredAt,
        storage = InstantStorage.TIMESTAMP_WITH_TIME_ZONE,
    )
    val optionalNativeOccurredAt = column(
        PropertyColumnModel::optionalNativeOccurredAt,
        storage = InstantStorage.TIMESTAMP_WITH_TIME_ZONE,
    )
    val ratio = column(PropertyColumnModel::ratio)
    val score = column(PropertyColumnModel::score)
    val enabled = column(PropertyColumnModel::enabled)
    val amount = column(PropertyColumnModel::amount, precision = 12, scale = 2)
    val publicId = column(PropertyColumnModel::publicId)
    val optionalId = column(PropertyColumnModel::optionalId)
    val content = column(PropertyColumnModel::content, maxSize = 4096)
    val jsonValue = column(PropertyColumnModel::jsonValue)
    val jsonObject = column(PropertyColumnModel::jsonObject)
    val jsonArray = column(PropertyColumnModel::jsonArray)
    val status = column(PropertyColumnModel::status)
    val optionalStatus = column(PropertyColumnModel::optionalStatus)
    val tags = column(PropertyColumnModel::tags)
    val optionalTags = column(PropertyColumnModel::optionalTags)
    val statuses = column(PropertyColumnModel::statuses)
    val optionalStatuses = column(PropertyColumnModel::optionalStatuses)
    val statusIds = column(PropertyColumnModel::statusIds)
    val statusSet = column(PropertyColumnModel::statusSet)
    val optionalStatusSet = column(PropertyColumnModel::optionalStatusSet)
    val numbers = column(PropertyColumnModel::numbers)
    val optionalNumbers = column(PropertyColumnModel::optionalNumbers)
    val payload = column(PropertyColumnModel::payload, PropertyColumnPayload)
    val optionalPayload = column(PropertyColumnModel::optionalPayload, PropertyColumnPayload)
    val payloads = column(PropertyColumnModel::payloads, PropertyColumnPayload)
    val optionalPayloads = column(PropertyColumnModel::optionalPayloads, PropertyColumnPayload)
    val parentId = column(
        PropertyColumnModel::parentId,
        references = PropertyColumnParents.id,
        onDelete = ReferenceOption.CASCADE,
    )
    val optionalParentId = column(
        PropertyColumnModel::optionalParentId,
        references = PropertyColumnParents.id,
    )
}

private class PropertyForeignKeyModel(
    val simpleId: Long,
    val optionalSimpleId: Long?,
)

private object PropertyForeignKeyTable : Table("property_foreign_keys") {
    val simpleId = fkey(PropertyForeignKeyModel::simpleId, SimpleTable)
    val optionalSimpleId = fkey(
        PropertyForeignKeyModel::optionalSimpleId,
        SimpleTable,
        ReferenceOption.NO_ACTION,
        name = "fallback_simple_id",
    )
}

private class PropertyUuidForeignKeyModel(
    val uuidSimpleId: UUID,
    val optionalUuidSimpleId: UUID?,
)

private object PropertyUuidForeignKeyTable : Table("property_uuid_foreign_keys") {
    val uuidSimpleId = fkey(PropertyUuidForeignKeyModel::uuidSimpleId, UuidSimpleTable)
    val optionalUuidSimpleId = fkey(
        PropertyUuidForeignKeyModel::optionalUuidSimpleId,
        UuidSimpleTable,
        ReferenceOption.NO_ACTION,
        name = "fallback_uuid_simple_id",
    )
}

class PropertyColumnsTest {

    @Test
    fun `derives conventional snake case column names`() {
        assertEquals("display_name", PropertyColumnModel::displayName.standardColumnName())
        assertEquals("url_value", NameExamples::URLValue.standardColumnName())
        assertEquals("version2_value", NameExamples::version2Value.standardColumnName())
        assertEquals("private_value", NameExamples::_privateValue.standardColumnName())
    }

    @Test
    fun `configures scalar columns from property types and nullability`() = withPostgresDialect {
        assertEquals("display_name", PropertyColumnTable.displayName.name)
        assertEquals("items", PropertyColumnTable.itemCount.name)
        assertEquals("VARCHAR(120)", PropertyColumnTable.displayName.columnType.sqlType())
        assertEquals("TEXT", PropertyColumnTable.optionalLabel.columnType.sqlType())
        assertEquals("SMALLINT", PropertyColumnTable.tinyValue.columnType.sqlType())
        assertEquals("SMALLINT", PropertyColumnTable.shortValue.columnType.sqlType())
        assertEquals("INT", PropertyColumnTable.itemCount.columnType.sqlType())
        assertEquals("BIGINT", PropertyColumnTable.createdAt.columnType.sqlType())
        assertEquals("BIGINT", PropertyColumnTable.occurredAt.columnType.sqlType())
        assertEquals("BIGINT", PropertyColumnTable.optionalOccurredAt.columnType.sqlType())
        assertEquals("TIMESTAMP WITH TIME ZONE", PropertyColumnTable.nativeOccurredAt.columnType.sqlType())
        assertEquals(
            "TIMESTAMP WITH TIME ZONE",
            PropertyColumnTable.optionalNativeOccurredAt.columnType.sqlType(),
        )
        assertEquals("created_at", PropertyColumnTable.createdAt.name)
        assertEquals("updated_at", PropertyColumnTable.updatedAt.name)
        assertEquals("REAL", PropertyColumnTable.ratio.columnType.sqlType())
        assertEquals("DOUBLE PRECISION", PropertyColumnTable.score.columnType.sqlType())
        assertEquals("BOOLEAN", PropertyColumnTable.enabled.columnType.sqlType())
        assertEquals("DECIMAL(12, 2)", PropertyColumnTable.amount.columnType.sqlType())
        assertEquals("uuid", PropertyColumnTable.publicId.columnType.sqlType().lowercase())
        assertEquals("bytea", PropertyColumnTable.content.columnType.sqlType())

        assertFalse(PropertyColumnTable.displayName.columnType.nullable)
        assertTrue(PropertyColumnTable.optionalLabel.columnType.nullable)
        assertTrue(PropertyColumnTable.shortValue.columnType.nullable)
        assertTrue(PropertyColumnTable.optionalId.columnType.nullable)
        assertTrue(PropertyColumnTable.updatedAt.columnType.nullable)
        assertFalse(PropertyColumnTable.occurredAt.columnType.nullable)
        assertTrue(PropertyColumnTable.optionalOccurredAt.columnType.nullable)
        assertFalse(PropertyColumnTable.nativeOccurredAt.columnType.nullable)
        assertTrue(PropertyColumnTable.optionalNativeOccurredAt.columnType.nullable)
    }

    @Test
    fun `configures keep-specific json enum collection and reference columns`() = withPostgresDialect {
        assertEquals("JSONB", PropertyColumnTable.jsonValue.columnType.sqlType())
        assertEquals("JSONB", PropertyColumnTable.jsonObject.columnType.sqlType())
        assertEquals("JSONB", PropertyColumnTable.jsonArray.columnType.sqlType())
        assertEquals("INTEGER", PropertyColumnTable.status.columnType.sqlType())
        assertEquals("status_id", PropertyColumnTable.status.name)
        assertEquals("optional_status_id", PropertyColumnTable.optionalStatus.name)
        assertTrue(PropertyColumnTable.optionalStatus.columnType.nullable)
        assertEquals("TEXT[]", PropertyColumnTable.tags.columnType.sqlType())
        assertEquals("INTEGER[]", PropertyColumnTable.statuses.columnType.sqlType())
        assertEquals("statuses_ids", PropertyColumnTable.statuses.name)
        assertEquals("optional_statuses_ids", PropertyColumnTable.optionalStatuses.name)
        assertEquals("status_ids", PropertyColumnTable.statusIds.name)
        assertEquals("INTEGER[]", PropertyColumnTable.statusSet.columnType.sqlType())
        assertEquals("status_set_ids", PropertyColumnTable.statusSet.name)
        assertEquals("optional_status_set_ids", PropertyColumnTable.optionalStatusSet.name)
        assertTrue(PropertyColumnTable.optionalStatusSet.columnType.nullable)
        assertEquals("BIGINT[]", PropertyColumnTable.numbers.columnType.sqlType())
        assertTrue(PropertyColumnTable.optionalNumbers.columnType.nullable)
        assertEquals("JSONB", PropertyColumnTable.payload.columnType.sqlType())
        assertEquals("JSONB", PropertyColumnTable.payloads.columnType.sqlType())
        assertEquals(
            PropertyColumnParents.id,
            PropertyColumnTable.parentId.foreignKey?.targetOf(PropertyColumnTable.parentId),
        )
        assertTrue(PropertyColumnTable.optionalParentId.columnType.nullable)
    }

    @Test
    fun `configures conventional long foreign keys from properties`() {
        assertEquals("simple_id", PropertyForeignKeyTable.simpleId.name)
        assertEquals("fallback_simple_id", PropertyForeignKeyTable.optionalSimpleId.name)
        assertFalse(PropertyForeignKeyTable.simpleId.columnType.nullable)
        assertTrue(PropertyForeignKeyTable.optionalSimpleId.columnType.nullable)
        assertEquals(
            "property_foreign_keys_simple_id_fk",
            PropertyForeignKeyTable.simpleId.foreignKey?.customFkName,
        )
        assertEquals(
            "property_foreign_keys_fallback_simple_id_fk",
            PropertyForeignKeyTable.optionalSimpleId.foreignKey?.customFkName,
        )
        assertTrue(PropertyForeignKeyTable.indices.any { it.indexName == "property_foreign_keys_simple_id_ix" })
        assertTrue(
            PropertyForeignKeyTable.indices.any {
                it.indexName == "property_foreign_keys_fallback_simple_id_ix"
            }
        )
    }

    @Test
    fun `configures conventional UUID foreign keys from properties`() {
        assertEquals("uuid_simple_id", PropertyUuidForeignKeyTable.uuidSimpleId.name)
        assertEquals("fallback_uuid_simple_id", PropertyUuidForeignKeyTable.optionalUuidSimpleId.name)
        assertFalse(PropertyUuidForeignKeyTable.uuidSimpleId.columnType.nullable)
        assertTrue(PropertyUuidForeignKeyTable.optionalUuidSimpleId.columnType.nullable)
        assertEquals(
            "property_uuid_foreign_keys_uuid_simple_id_fk",
            PropertyUuidForeignKeyTable.uuidSimpleId.foreignKey?.customFkName,
        )
        assertEquals(
            "property_uuid_foreign_keys_fallback_uuid_simple_id_fk",
            PropertyUuidForeignKeyTable.optionalUuidSimpleId.foreignKey?.customFkName,
        )
        assertTrue(
            PropertyUuidForeignKeyTable.indices.any {
                it.indexName == "property_uuid_foreign_keys_uuid_simple_id_ix"
            }
        )
        assertTrue(
            PropertyUuidForeignKeyTable.indices.any {
                it.indexName == "property_uuid_foreign_keys_fallback_uuid_simple_id_ix"
            }
        )
    }

    @Test
    fun `supports case insensitive text and validates incompatible string options`() {
        val table = object : Table("property_column_options") {}

        val caseInsensitive = table.column(
            PropertyColumnModel::displayName,
            maxSize = 253,
            caseInsensitive = true,
        )
        assertEquals("CITEXT", caseInsensitive.columnType.sqlType())

        assertFailsWith<IllegalArgumentException> {
            table.column(PropertyColumnModel::displayName, maxSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            table.column(PropertyColumnModel::displayName, encrypted = true, caseInsensitive = true)
        }
        assertFailsWith<IllegalArgumentException> {
            table.column(PropertyColumnModel::content, encrypted = true, maxSize = 100)
        }
    }

    @Test
    fun `preserves an explicit data enum column name`() {
        val table = object : Table("property_enum_name") {}

        val status = table.column(PropertyColumnModel::status, name = "state")
        val statuses = table.column(PropertyColumnModel::statuses, name = "states")

        assertEquals("state", status.name)
        assertEquals("states", statuses.name)
    }

    @Suppress("PropertyName", "unused")
    private class NameExamples(
        val URLValue: String,
        val version2Value: String,
        val _privateValue: String,
    )

    private fun <T> withPostgresDialect(block: () -> T): T =
        withOfflinePostgresDdlContext(PostgresTargetVersion(), block)
}
