package org.tekfive.keep.audit

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.JsonValue
import org.tekfive.jfk.ToJsonObject
import org.tekfive.keep.data.IdentifiedData
import org.tekfive.keep.data.UuidData
import java.time.Duration
import java.time.temporal.TemporalAccessor
import java.util.UUID

/** The pinned JFK serializer does not encode UUID or Java time values by default. */
internal fun <D : UuidData, W : Any> defaultAuditSnapshot(
    record: D,
    state: AuditStateBinding<D, W>,
    codec: AuditWriterCodec<W>,
    excluded: Set<String>,
): JsonObject {
    val implementation = record.javaClass.getMethod("toJsonObject").declaringClass
    // Respect an application override, including lossless numeric representations.
    if (implementation != IdentifiedData::class.java && implementation != ToJsonObject::class.java) {
        return record.toJsonObject()
    }
    val result = JsonObject()
    fun put(name: String, value: Any?) {
        if (name.removePrefix("_") in excluded || (value == null && !record.includeNullProperties)) return
        result[name] = if (name.removePrefix("_") == state.createdBy.name.removePrefix("_")) {
            codec.encode(state.createdBy.get(record))
        } else snapshotValue(value)
    }
    for (property in record.jsonProperties) {
        if (property.name.removePrefix("_") !in excluded) put(property.name, property.getter.call(record))
    }
    record.additionalJsonValues().forEach(::put)
    return result
}

private fun snapshotValue(value: Any?): Any? = when (value) {
    null -> null
    is JsonValue -> value
    is UUID, is TemporalAccessor, is Duration -> value.toString()
    is ToJsonObject -> value.toJsonObject()
    is Collection<*> -> value.map(::snapshotValue)
    is Map<*, *> -> value.entries.associate { entry ->
        require(entry.key is String) { "Snapshot object keys must be strings." }
        entry.key as String to snapshotValue(entry.value)
    }
    else -> value
}
