package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone as exposedTimestampWithTimeZone
import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.JsonValue
import org.tekfive.jfk.ToJsonObject
import org.tekfive.keep.array.setArray
import org.tekfive.keep.encryption.encryptedBinary
import org.tekfive.keep.encryption.encryptedDataEnum
import org.tekfive.keep.encryption.encryptedDataEnumList
import org.tekfive.keep.encryption.encryptedJsonObject
import org.tekfive.keep.encryption.encryptedJsonb
import org.tekfive.keep.encryption.encryptedJsonbList
import org.tekfive.keep.encryption.encryptedStringList
import org.tekfive.keep.encryption.encryptedText
import org.tekfive.keep.json.jsonArray
import org.tekfive.keep.json.jsonObject
import org.tekfive.keep.json.jsonObjectList
import org.tekfive.keep.json.jsonValue
import org.tekfive.keep.json.toFromJson
import org.tekfive.keep.json.toFromJsonArray
import org.tekfive.keep.text.citext
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.JvmName
import kotlin.reflect.KProperty1

private val acronymBoundary = Regex("([A-Z]+)([A-Z][a-z])")
private val wordBoundary = Regex("([a-z0-9])([A-Z])")

@PublishedApi
internal fun KProperty1<*, *>.standardIdColumnName(): String =
    standardColumnName().let { if (it.endsWith("_id")) it else "${it}_id" }

@PublishedApi
internal fun KProperty1<*, *>.standardIdsColumnName(): String =
    standardColumnName().let { if (it.endsWith("_ids")) it else "${it}_ids" }

/** Converts a Kotlin property name such as `minimumStartAt` or `URLValue` to PostgreSQL snake case. */
fun KProperty1<*, *>.standardColumnName(): String = name
    .trimStart('_')
    .replace(acronymBoundary, "$1_$2")
    .replace(wordBoundary, "$1_$2")
    .lowercase()

/**
 * Returns [group] for a property of its mapped value type, preserving the concrete group type.
 *
 * The group must already have created its columns on this table; their names and nullability
 * remain defined by the group. The supplied [property] determines automatic Data mapping,
 * independently of the Kotlin name used for the returned group on the table.
 */
fun <D, E, G : ColumnGroup<E>> Table.column(
    property: KProperty1<D, E>,
    group: G,
): G {
    require(group.columns.all { it.table === this }) {
        "Column group for '${property.name}' must contain only columns from table '$tableName'"
    }
    PropertyColumnMappings.bind(group, property)
    return group
}

/**
 * Registers a column whose SQL type and nullability are inferred from [property].
 *
 * A name is derived from the Kotlin property using [standardColumnName] unless [name] is supplied.
 * The column retains [property] as [dataProperty] for Data mapping regardless of its table property name.
 * Strings use `TEXT` by default, `VARCHAR` when [maxSize] is supplied, `CITEXT` when
 * [caseInsensitive] is true, and encrypted `BYTEA` storage when [encrypted] is true.
 */
@JvmName("columnString")
fun <D> Table.column(
    property: KProperty1<D, String>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
    maxSize: Int? = null,
    caseInsensitive: Boolean = false,
): Column<String> = configureStringColumn(name, encrypted, maxSize, caseInsensitive).withDataProperty(property)

@JvmName("columnNullableString")
fun <D> Table.column(
    property: KProperty1<D, String?>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
    maxSize: Int? = null,
    caseInsensitive: Boolean = false,
): Column<String?> =
    configureStringColumn(name, encrypted, maxSize, caseInsensitive).nullable().withDataProperty(property)

private fun Table.configureStringColumn(
    name: String,
    encrypted: Boolean,
    maxSize: Int?,
    caseInsensitive: Boolean,
): Column<String> {
    require(name.isNotBlank()) { "Column name must not be blank" }
    require(maxSize == null || maxSize > 0) { "String maxSize must be greater than zero" }
    require(!encrypted || !caseInsensitive) { "Encrypted text cannot be case insensitive" }
    require(!encrypted || maxSize == null) { "Encrypted text cannot enforce maxSize" }

    return when {
        encrypted -> encryptedText(name)
        caseInsensitive -> citext(name, maxSize ?: Int.MAX_VALUE)
        maxSize != null -> varchar(name, maxSize)
        else -> text(name)
    }
}

@JvmName("columnByte")
fun <D> Table.column(
    property: KProperty1<D, Byte>,
    name: String = property.standardColumnName(),
): Column<Byte> = byte(name).withDataProperty(property)

@JvmName("columnNullableByte")
fun <D> Table.column(
    property: KProperty1<D, Byte?>,
    name: String = property.standardColumnName(),
): Column<Byte?> = byte(name).nullable().withDataProperty(property)

@JvmName("columnShort")
fun <D> Table.column(
    property: KProperty1<D, Short>,
    name: String = property.standardColumnName(),
): Column<Short> = short(name).withDataProperty(property)

@JvmName("columnNullableShort")
fun <D> Table.column(
    property: KProperty1<D, Short?>,
    name: String = property.standardColumnName(),
): Column<Short?> = short(name).nullable().withDataProperty(property)

@JvmName("columnInt")
fun <D> Table.column(
    property: KProperty1<D, Int>,
    name: String = property.standardColumnName(),
): Column<Int> = integer(name).withDataProperty(property)

@JvmName("columnNullableInt")
fun <D> Table.column(
    property: KProperty1<D, Int?>,
    name: String = property.standardColumnName(),
): Column<Int?> = integer(name).nullable().withDataProperty(property)

@JvmName("columnLong")
fun <D> Table.column(
    property: KProperty1<D, Long>,
    name: String = property.standardColumnName(),
    timestamp: Boolean = false,
): Column<Long> = (if (timestamp) timestamp(name) else long(name)).withDataProperty(property)

@JvmName("columnNullableLong")
fun <D> Table.column(
    property: KProperty1<D, Long?>,
    name: String = property.standardColumnName(),
    timestamp: Boolean = false,
): Column<Long?> = (if (timestamp) timestamp(name) else long(name)).nullable().withDataProperty(property)

/**
 * Registers an [Instant] column using epoch-millisecond [InstantStorage.BIGINT_EPOCH_MILLIS]
 * storage by default. Select [InstantStorage.TIMESTAMP_WITH_TIME_ZONE] for a native PostgreSQL
 * temporal column.
 */
@JvmName("columnInstant")
fun <D> Table.column(
    property: KProperty1<D, Instant>,
    name: String = property.standardColumnName(),
    storage: InstantStorage = InstantStorage.BIGINT_EPOCH_MILLIS,
): Column<Instant> = instantColumn(name, storage).withDataProperty(property)

/** Nullable counterpart to [column] for an [Instant] property. */
@JvmName("columnNullableInstant")
fun <D> Table.column(
    property: KProperty1<D, Instant?>,
    name: String = property.standardColumnName(),
    storage: InstantStorage = InstantStorage.BIGINT_EPOCH_MILLIS,
): Column<Instant?> = instantColumn(name, storage).nullable().withDataProperty(property)

private fun Table.instantColumn(name: String, storage: InstantStorage): Column<Instant> = when (storage) {
    InstantStorage.BIGINT_EPOCH_MILLIS -> long(name).transform(
        wrap = Instant::ofEpochMilli,
        unwrap = Instant::toEpochMilli,
    )

    InstantStorage.TIMESTAMP_WITH_TIME_ZONE -> exposedTimestampWithTimeZone(name).transform(
        wrap = { it.toInstant() },
        unwrap = { it.atOffset(ZoneOffset.UTC) },
    )
}

@JvmName("columnFloat")
fun <D> Table.column(
    property: KProperty1<D, Float>,
    name: String = property.standardColumnName(),
): Column<Float> = float(name).withDataProperty(property)

@JvmName("columnNullableFloat")
fun <D> Table.column(
    property: KProperty1<D, Float?>,
    name: String = property.standardColumnName(),
): Column<Float?> = float(name).nullable().withDataProperty(property)

@JvmName("columnDouble")
fun <D> Table.column(
    property: KProperty1<D, Double>,
    name: String = property.standardColumnName(),
): Column<Double> = double(name).withDataProperty(property)

@JvmName("columnNullableDouble")
fun <D> Table.column(
    property: KProperty1<D, Double?>,
    name: String = property.standardColumnName(),
): Column<Double?> = double(name).nullable().withDataProperty(property)

@JvmName("columnBoolean")
fun <D> Table.column(
    property: KProperty1<D, Boolean>,
    name: String = property.standardColumnName(),
): Column<Boolean> = bool(name).withDataProperty(property)

@JvmName("columnNullableBoolean")
fun <D> Table.column(
    property: KProperty1<D, Boolean?>,
    name: String = property.standardColumnName(),
): Column<Boolean?> = bool(name).nullable().withDataProperty(property)

@JvmName("columnDecimal")
fun <D> Table.column(
    property: KProperty1<D, BigDecimal>,
    precision: Int,
    scale: Int,
    name: String = property.standardColumnName(),
): Column<BigDecimal> = decimal(name, precision, scale).withDataProperty(property)

@JvmName("columnNullableDecimal")
fun <D> Table.column(
    property: KProperty1<D, BigDecimal?>,
    precision: Int,
    scale: Int,
    name: String = property.standardColumnName(),
): Column<BigDecimal?> = decimal(name, precision, scale).nullable().withDataProperty(property)

@JvmName("columnJavaUuid")
fun <D> Table.column(
    property: KProperty1<D, UUID>,
    name: String = property.standardColumnName(),
): Column<UUID> = javaUUID(name).withDataProperty(property)

@JvmName("columnNullableJavaUuid")
fun <D> Table.column(
    property: KProperty1<D, UUID?>,
    name: String = property.standardColumnName(),
): Column<UUID?> = javaUUID(name).nullable().withDataProperty(property)

@JvmName("columnBinary")
fun <D> Table.column(
    property: KProperty1<D, ByteArray>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
    maxSize: Int? = null,
): Column<ByteArray> = configureBinaryColumn(name, encrypted, maxSize).withDataProperty(property)

@JvmName("columnNullableBinary")
fun <D> Table.column(
    property: KProperty1<D, ByteArray?>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
    maxSize: Int? = null,
): Column<ByteArray?> = configureBinaryColumn(name, encrypted, maxSize).nullable().withDataProperty(property)

private fun Table.configureBinaryColumn(
    name: String,
    encrypted: Boolean,
    maxSize: Int?,
): Column<ByteArray> {
    require(name.isNotBlank()) { "Column name must not be blank" }
    require(maxSize == null || maxSize > 0) { "Binary maxSize must be greater than zero" }
    require(!encrypted || maxSize == null) { "Encrypted binary data cannot enforce maxSize" }
    return when {
        encrypted -> encryptedBinary(name)
        maxSize != null -> binary(name, maxSize)
        else -> binary(name)
    }
}

@JvmName("columnJsonValue")
fun <D> Table.column(
    property: KProperty1<D, JsonValue>,
    name: String = property.standardColumnName(),
): Column<JsonValue> = jsonValue(name).withDataProperty(property)

@JvmName("columnNullableJsonValue")
fun <D> Table.column(
    property: KProperty1<D, JsonValue?>,
    name: String = property.standardColumnName(),
): Column<JsonValue?> = jsonValue(name).nullable().withDataProperty(property)

@JvmName("columnJsonObject")
fun <D> Table.column(
    property: KProperty1<D, JsonObject>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<JsonObject> = (if (encrypted) encryptedJsonObject(name) else jsonObject(name)).withDataProperty(property)

@JvmName("columnNullableJsonObject")
fun <D> Table.column(
    property: KProperty1<D, JsonObject?>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<JsonObject?> =
    (if (encrypted) encryptedJsonObject(name) else jsonObject(name)).nullable().withDataProperty(property)

@JvmName("columnJsonArray")
fun <D> Table.column(
    property: KProperty1<D, JsonArray>,
    name: String = property.standardColumnName(),
): Column<JsonArray> = jsonArray(name).withDataProperty(property)

@JvmName("columnNullableJsonArray")
fun <D> Table.column(
    property: KProperty1<D, JsonArray?>,
    name: String = property.standardColumnName(),
): Column<JsonArray?> = jsonArray(name).nullable().withDataProperty(property)

/** Stores a list of [JsonObject] values as a JSONB [JsonArray]. */
@JvmName("columnJsonObjectList")
fun <D> Table.column(
    property: KProperty1<D, List<JsonObject>>,
    name: String = property.standardColumnName(),
): Column<List<JsonObject>> = jsonObjectList(name).withDataProperty(property)

/** Nullable counterpart to [column] for a list of [JsonObject] values. */
@JvmName("columnNullableJsonObjectList")
fun <D> Table.column(
    property: KProperty1<D, List<JsonObject>?>,
    name: String = property.standardColumnName(),
): Column<List<JsonObject>?> = jsonObjectList(name).nullable().withDataProperty(property)

@JvmName("columnDataEnum")
inline fun <D, reified E> Table.column(
    property: KProperty1<D, E>,
    name: String = property.standardIdColumnName(),
    encrypted: Boolean = false,
): Column<E> where E : Enum<E>, E : DataEnum =
    (if (encrypted) encryptedDataEnum<E>(name) else dataEnum<E>(name)).withDataProperty(property)

@JvmName("columnNullableDataEnum")
inline fun <D, reified E> Table.column(
    property: KProperty1<D, E?>,
    name: String = property.standardIdColumnName(),
    encrypted: Boolean = false,
): Column<E?> where E : Enum<E>, E : DataEnum =
    (if (encrypted) encryptedDataEnum<E>(name) else dataEnum<E>(name)).nullable().withDataProperty(property)

/** Stores strings as TEXT[], or VARCHAR([maxSize])[] with a character limit per element. */
@JvmName("columnStringList")
fun <D> Table.column(
    property: KProperty1<D, List<String>>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
    maxSize: Int? = null,
): Column<List<String>> = configureStringListColumn(name, encrypted, maxSize).withDataProperty(property)

/** Nullable counterpart for a list of strings, with an optional character limit per element. */
@JvmName("columnNullableStringList")
fun <D> Table.column(
    property: KProperty1<D, List<String>?>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
    maxSize: Int? = null,
): Column<List<String>?> =
    configureStringListColumn(name, encrypted, maxSize).nullable().withDataProperty(property)

private fun Table.configureStringListColumn(
    name: String,
    encrypted: Boolean,
    maxSize: Int?,
): Column<List<String>> {
    require(name.isNotBlank()) { "Column name must not be blank" }
    require(maxSize == null || maxSize > 0) { "String list maxSize must be greater than zero" }
    require(!encrypted || maxSize == null) { "Encrypted string lists cannot enforce maxSize" }
    return when {
        encrypted -> encryptedStringList(name)
        maxSize != null -> array(name, VarCharColumnType(maxSize))
        else -> array<String>(name)
    }
}

@JvmName("columnDataEnumList")
inline fun <D, reified E> Table.column(
    property: KProperty1<D, List<E>>,
    name: String = property.standardIdsColumnName(),
    encrypted: Boolean = false,
): Column<List<E>> where E : Enum<E>, E : DataEnum =
    (if (encrypted) encryptedDataEnumList<E>(name) else dataEnumList<E>(name)).withDataProperty(property)

@JvmName("columnNullableDataEnumList")
inline fun <D, reified E> Table.column(
    property: KProperty1<D, List<E>?>,
    name: String = property.standardIdsColumnName(),
    encrypted: Boolean = false,
): Column<List<E>?> where E : Enum<E>, E : DataEnum =
    (if (encrypted) encryptedDataEnumList<E>(name) else dataEnumList<E>(name)).nullable().withDataProperty(property)

@JvmName("columnDataEnumSet")
inline fun <D, reified E> Table.column(
    property: KProperty1<D, Set<E>>,
    name: String = property.standardIdsColumnName(),
): Column<Set<E>> where E : Enum<E>, E : DataEnum = dataEnumSet<E>(name).withDataProperty(property)

@JvmName("columnNullableDataEnumSet")
inline fun <D, reified E> Table.column(
    property: KProperty1<D, Set<E>?>,
    name: String = property.standardIdsColumnName(),
): Column<Set<E>?> where E : Enum<E>, E : DataEnum = dataEnumSet<E>(name).nullable().withDataProperty(property)

@JvmName("columnSet")
inline fun <D, reified E : Any> Table.column(
    property: KProperty1<D, Set<E>>,
    name: String = property.standardColumnName(),
): Column<Set<E>> = setArray<E>(name).withDataProperty(property)

@JvmName("columnNullableSet")
inline fun <D, reified E : Any> Table.column(
    property: KProperty1<D, Set<E>?>,
    name: String = property.standardColumnName(),
): Column<Set<E>?> = setArray<E>(name).nullable().withDataProperty(property)

@JvmName("columnJsonObjectValue")
fun <D, T> Table.column(
    property: KProperty1<D, T>,
    fromJson: FromJsonObject<T>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<T> where T : Any, T : ToJsonObject =
    (if (encrypted) encryptedJsonb(name, fromJson) else toFromJson(name, fromJson)).withDataProperty(property)

@JvmName("columnNullableJsonObjectValue")
fun <D, T> Table.column(
    property: KProperty1<D, T?>,
    fromJson: FromJsonObject<T>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<T?> where T : Any, T : ToJsonObject =
    (if (encrypted) encryptedJsonb(name, fromJson) else toFromJson(name, fromJson)).nullable().withDataProperty(property)

@JvmName("columnJsonObjectValueList")
fun <D, T> Table.column(
    property: KProperty1<D, List<T>>,
    fromJson: FromJsonObject<T>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<List<T>> where T : Any, T : ToJsonObject =
    (if (encrypted) encryptedJsonbList(name, fromJson) else toFromJsonArray(name, fromJson)).withDataProperty(property)

@JvmName("columnNullableJsonObjectValueList")
fun <D, T> Table.column(
    property: KProperty1<D, List<T>?>,
    fromJson: FromJsonObject<T>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<List<T>?> where T : Any, T : ToJsonObject =
    (if (encrypted) encryptedJsonbList(name, fromJson) else toFromJsonArray(name, fromJson)).nullable().withDataProperty(property)

@JvmName("columnReference")
fun <D, T : Any> Table.column(
    property: KProperty1<D, T>,
    references: Column<T>,
    name: String = property.standardColumnName(),
    onDelete: ReferenceOption? = null,
    onUpdate: ReferenceOption? = null,
    foreignKeyName: String? = null,
): Column<T> = reference(name, references, onDelete, onUpdate, foreignKeyName).withDataProperty(property)

@JvmName("columnNullableReference")
fun <D, T : Any> Table.column(
    property: KProperty1<D, T?>,
    references: Column<T>,
    name: String = property.standardColumnName(),
    onDelete: ReferenceOption? = null,
    onUpdate: ReferenceOption? = null,
    foreignKeyName: String? = null,
): Column<T?> = reference(name, references, onDelete, onUpdate, foreignKeyName).nullable().withDataProperty(property)
