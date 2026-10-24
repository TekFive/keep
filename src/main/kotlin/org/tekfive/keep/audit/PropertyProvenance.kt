package org.tekfive.keep.audit

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.json.jsonb
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asRequiredJsonArray
import org.tekfive.keep.data.withDataProperty
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KProperty1

/** The writer and transaction time of a property's most recent change. */
data class PropertyProvenance<W : Any>(val propertyId: UUID, val writer: W, val writtenAt: Instant)

/** Explicit generic JSON mapping; writer decoding is owned by [codec], not reflection. */
fun <D : Any, W : Any> Table.auditProvenances(
    property: KProperty1<D, List<PropertyProvenance<W>>>,
    codec: AuditWriterCodec<W>,
    name: String = "property_provenances",
): Column<List<PropertyProvenance<W>>> = jsonb<List<PropertyProvenance<W>>>(
    name,
    serialize = { values ->
        JsonArray(values.map { value ->
            JsonObject().apply {
                this["propertyId"] = value.propertyId.toString()
                this["writer"] = codec.encode(value.writer)
                this["writtenAt"] = value.writtenAt.toString()
            }
        }).toJsonString()
    },
    deserialize = { text ->
        text.asRequiredJsonArray().items.map { item ->
            val json = item as JsonObject
            PropertyProvenance(
                UUID.fromString(json["propertyId"].string),
                codec.decode(json["writer"] as JsonObject),
                Instant.parse(json["writtenAt"].string),
            )
        }
    },
).withDataProperty(property)
