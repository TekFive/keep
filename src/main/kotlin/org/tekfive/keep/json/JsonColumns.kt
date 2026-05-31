package org.tekfive.keep.json

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.json.jsonb
import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.Json
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.JsonValue
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.asRequiredJsonArray
import org.tekfive.jfk.asRequiredJsonObject


/** Registers a JSONB column that maps to any jfk [JsonValue]. */
fun Table.jsonValue(name: String): Column<JsonValue> = jsonb(
    name,
    serialize = { it.toJsonString() },
    deserialize = { Json.parse(it) },
)

/** Registers a JSONB column that maps to a jfk [JsonObject]. */
fun Table.jsonObject(name: String): Column<JsonObject> = jsonb(
    name,
    serialize = { it.toJsonString() },
    deserialize = { it.asRequiredJsonObject() },
)

/** Registers a JSONB column that maps to a raw jfk [JsonArray] without requiring a [org.tekfive.keep.json.JsonArrayEncoder]. */
fun Table.jsonArray(name: String): Column<JsonArray> = jsonb(
    name,
    serialize = { it.toJsonString() },
    deserialize = { it.asRequiredJsonArray() },
)


/** Registers a JSONB column that stores a single [T] object, serialized via [ToJsonObject]/[FromJsonObject]. */
fun <T> Table.toFromJson(name: String, fromJson: FromJsonObject<T>): Column<T> where T : Any, T : ToJsonObject = jsonb(
    name,
    serialize = { it.toJsonObject().toJsonString() },
    deserialize = { fromJson.fromJson(it.asRequiredJsonObject()) },
)

/** Registers a JSONB column that stores a list of [T] objects, serialized via [ToJsonObject]/[FromJsonObject]. */
fun <T> Table.toFromJsonArray(name: String, fromJson: FromJsonObject<T>): Column<List<T>> where T : Any, T : ToJsonObject = jsonb<List<T>>(
    name,
    serialize = { list -> JsonArray(list.map { it.toJsonObject() }).toJsonString() },
    deserialize = { str ->
        val array = str.asRequiredJsonArray()
        array.items.map { fromJson.fromJson(it as JsonObject) }
    },
)
