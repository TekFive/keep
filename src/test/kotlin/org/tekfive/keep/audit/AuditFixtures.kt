package org.tekfive.keep.audit

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.keep.data.ColumnGroup
import org.tekfive.keep.data.UuidData
import org.tekfive.keep.data.column
import org.tekfive.keep.utils.ColumnValueMapper
import java.time.Instant
import java.util.UUID

data class TestWriter(val kind: String, val id: UUID) : ToJsonObject {
    override fun toJsonObject() = JsonObject(mapOf("kind" to kind, "id" to id.toString()))
}

object TestWriterCodec : AuditWriterCodec<TestWriter> {
    override fun columns(table: Table): ColumnGroup<TestWriter> = object : ColumnGroup<TestWriter> {
        val kind = table.varchar("writer_kind", 50)
        val id = table.javaUUID("writer_id")
        override val columns: List<Column<*>> = listOf(kind, id)
        override fun map(row: ResultRow) = TestWriter(row[kind], row[id])
        override fun mapColumns(value: TestWriter, statement: ColumnValueMapper) {
            statement[kind] = value.kind
            statement[id] = value.id
        }
    }
    override fun encode(writer: TestWriter): JsonObject = writer.toJsonObject()
    override fun decode(json: JsonObject) = TestWriter(checkNotNull(json["kind"].string), UUID.fromString(json["id"].string))
}

val NAME_ID: UUID = UUID.fromString("e6321979-56d2-4c87-b4f6-013c17afc632")
val NOTE_ID: UUID = UUID.fromString("bbeb176e-4901-401c-a8ef-6ad87a3c1295")
val SECRET_ID: UUID = UUID.fromString("fdbd2c5e-b2d7-4121-8f1e-3f0dc50b585a")
val PINS_ID: UUID = UUID.fromString("0e2ee97f-f580-4274-99a0-48ea0296f326")
val CHILDREN_ID: UUID = UUID.fromString("d5ae09d6-dffd-4423-b232-c7b833669f48")
val FIRST_VALUE: UUID = UUID.fromString("b2fec4c5-175a-4da2-9ced-775f9c4a81f1")
val SECOND_VALUE: UUID = UUID.fromString("78ff4fd7-42b3-4f48-9e79-4a94cb67bd2e")
val creator = TestWriter("person", UUID.fromString("c2a8eaac-7ef6-48f8-b37c-a6cb72f19129"))
val editor = TestWriter("process", UUID.fromString("c2a8eaac-7ef6-48f8-b37c-a6cb72f19130"))

class AuditRow(
    var name: String,
    var note: String? = null,
    var secret: String? = null,
    var pins: List<UUID> = emptyList(),
    var values: JsonObject = JsonObject(),
    var createdBy: TestWriter = creator,
    var provenances: List<PropertyProvenance<TestWriter>> = emptyList(),
    var calculatedLabel: String = "derived",
    val addedAt: Instant = Instant.EPOCH,
) : UuidData() {
    var originalValues: JsonObject = JsonObject()
}

class JsonValues : AuditValueContributor<AuditRow> {
    override fun initialIds(data: AuditRow): Set<UUID> = data.values.entries.keys.mapTo(mutableSetOf(), UUID::fromString)
    override fun changedIds(data: AuditRow): Set<UUID> =
        (data.values.entries.keys + data.originalValues.entries.keys).filterTo(mutableSetOf()) {
            data.values.entries[it]?.toJsonString() != data.originalValues.entries[it]?.toJsonString()
        }.mapTo(mutableSetOf(), UUID::fromString)

    override fun merge(data: AuditRow, stored: AuditRow, changedIds: Set<UUID>) {
        val merged = stored.values.toJsonString().asRequiredJsonObject()
        for (id in changedIds) {
            val value = data.values.entries[id.toString()]
            if (value == null) merged.remove(id.toString()) else merged[id.toString()] = value
        }
        data.values = merged
    }
    override fun loaded(data: AuditRow) { data.originalValues = data.values.toJsonString().asRequiredJsonObject() }
    override fun propertyName(propertyId: UUID): String? = when (propertyId) {
        FIRST_VALUE -> "First value"
        SECOND_VALUE -> "Second value"
        else -> null
    }
}

open class AuditRows(name: String) : AuditedUuidTable<AuditRow, TestWriter>(name, TestWriterCodec) {
    val name = column(AuditRow::name).audited(NAME_ID.toString())
    val note = column(AuditRow::note).audited(NOTE_ID.toString())
    val secret = column(AuditRow::secret, encrypted = true).secret(SECRET_ID.toString())
    val pins = column(AuditRow::pins).audited(PINS_ID.toString())
    val values = column(AuditRow::values).contributedBy(JsonValues())
    val createdBy = column(AuditRow::createdBy, TestWriterCodec.columns(this))
    val provenances = auditProvenances(AuditRow::provenances, TestWriterCodec)
    val calculatedLabel = column(AuditRow::calculatedLabel).unaudited(excludeFromSnapshot = true)
    val addedAt = column(AuditRow::addedAt)
    val childRows = children("children", CHILDREN_ID.toString())
    override val auditState = AuditStateBinding(AuditRow::createdBy, AuditRow::provenances)
    var failSnapshot = false
    var before: (() -> Unit)? = null

    override fun beforeSave(data: AuditRow, writer: TestWriter) { before?.invoke() }
    override fun serializeSnapshot(data: AuditRow): JsonObject {
        check(!failSnapshot) { "Synthetic snapshot failure" }
        return super.serializeSnapshot(data).apply {
            this["custom"] = "kept"
            this["secret"] = "synthetic-serializer-secret"
            this["provenances"] = "must be removed"
            this["calculatedLabel"] = "must be removed"
        }
    }

    fun renameWithoutChangingProvenance(id: UUID, writer: TestWriter, rename: (List<AuditRow>) -> Unit): Int =
        mutateAudited(listOf(id), writer, setOf(NAME_ID), mutation = rename)
}

class ChildRow(
    val parentId: UUID,
    var name: String,
    var createdBy: TestWriter = creator,
    var provenances: List<PropertyProvenance<TestWriter>> = emptyList(),
) : UuidData()

class ChildRows(name: String, owner: AuditedChildren<TestWriter>) : AuditedUuidTable<ChildRow, TestWriter>(name, TestWriterCodec) {
    val parentId = column(ChildRow::parentId)
    val name = column(ChildRow::name).audited(NAME_ID.toString())
    val createdBy = column(ChildRow::createdBy, TestWriterCodec.columns(this))
    val provenances = auditProvenances(ChildRow::provenances, TestWriterCodec)
    override val auditState = AuditStateBinding(ChildRow::createdBy, ChildRow::provenances)
    override val parent = AuditedParent(owner, ChildRow::parentId)
    val childRows = children("children", CHILDREN_ID.toString())
}
