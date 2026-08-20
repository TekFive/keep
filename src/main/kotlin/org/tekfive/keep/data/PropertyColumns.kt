package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
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
import org.tekfive.keep.json.jsonValue
import org.tekfive.keep.json.toFromJson
import org.tekfive.keep.json.toFromJsonArray
import org.tekfive.keep.text.citext
import java.math.BigDecimal
import java.util.UUID
import kotlin.jvm.JvmName
import kotlin.reflect.KProperty1

private val acronymBoundary = Regex("([A-Z]+)([A-Z][a-z])")
private val wordBoundary = Regex("([a-z0-9])([A-Z])")

/** Converts a Kotlin property name such as `minimumStartAt` or `URLValue` to PostgreSQL snake case. */
fun KProperty1<*, *>.standardColumnName(): String = name
    .trimStart('_')
    .replace(acronymBoundary, "$1_$2")
    .replace(wordBoundary, "$1_$2")
    .lowercase()

/**
 * Registers a column whose SQL type and nullability are inferred from [property].
 *
 * A name is derived from the Kotlin property using [standardColumnName] unless [name] is supplied.
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
): Column<String> = configureStringColumn(name, encrypted, maxSize, caseInsensitive)

@JvmName("columnNullableString")
fun <D> Table.column(
    property: KProperty1<D, String?>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
    maxSize: Int? = null,
    caseInsensitive: Boolean = false,
): Column<String?> = configureStringColumn(name, encrypted, maxSize, caseInsensitive).nullable()

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
): Column<Byte> = byte(name)

@JvmName("columnNullableByte")
fun <D> Table.column(
    property: KProperty1<D, Byte?>,
    name: String = property.standardColumnName(),
): Column<Byte?> = byte(name).nullable()

@JvmName("columnShort")
fun <D> Table.column(
    property: KProperty1<D, Short>,
    name: String = property.standardColumnName(),
): Column<Short> = short(name)

@JvmName("columnNullableShort")
fun <D> Table.column(
    property: KProperty1<D, Short?>,
    name: String = property.standardColumnName(),
): Column<Short?> = short(name).nullable()

@JvmName("columnInt")
fun <D> Table.column(
    property: KProperty1<D, Int>,
    name: String = property.standardColumnName(),
): Column<Int> = integer(name)

@JvmName("columnNullableInt")
fun <D> Table.column(
    property: KProperty1<D, Int?>,
    name: String = property.standardColumnName(),
): Column<Int?> = integer(name).nullable()

@JvmName("columnLong")
fun <D> Table.column(
    property: KProperty1<D, Long>,
    name: String = property.standardColumnName(),
    timestamp: Boolean = false,
): Column<Long> = if (timestamp) timestamp(name) else long(name)

@JvmName("columnNullableLong")
fun <D> Table.column(
    property: KProperty1<D, Long?>,
    name: String = property.standardColumnName(),
    timestamp: Boolean = false,
): Column<Long?> = (if (timestamp) timestamp(name) else long(name)).nullable()

@JvmName("columnFloat")
fun <D> Table.column(
    property: KProperty1<D, Float>,
    name: String = property.standardColumnName(),
): Column<Float> = float(name)

@JvmName("columnNullableFloat")
fun <D> Table.column(
    property: KProperty1<D, Float?>,
    name: String = property.standardColumnName(),
): Column<Float?> = float(name).nullable()

@JvmName("columnDouble")
fun <D> Table.column(
    property: KProperty1<D, Double>,
    name: String = property.standardColumnName(),
): Column<Double> = double(name)

@JvmName("columnNullableDouble")
fun <D> Table.column(
    property: KProperty1<D, Double?>,
    name: String = property.standardColumnName(),
): Column<Double?> = double(name).nullable()

@JvmName("columnBoolean")
fun <D> Table.column(
    property: KProperty1<D, Boolean>,
    name: String = property.standardColumnName(),
): Column<Boolean> = bool(name)

@JvmName("columnNullableBoolean")
fun <D> Table.column(
    property: KProperty1<D, Boolean?>,
    name: String = property.standardColumnName(),
): Column<Boolean?> = bool(name).nullable()

@JvmName("columnDecimal")
fun <D> Table.column(
    property: KProperty1<D, BigDecimal>,
    precision: Int,
    scale: Int,
    name: String = property.standardColumnName(),
): Column<BigDecimal> = decimal(name, precision, scale)

@JvmName("columnNullableDecimal")
fun <D> Table.column(
    property: KProperty1<D, BigDecimal?>,
    precision: Int,
    scale: Int,
    name: String = property.standardColumnName(),
): Column<BigDecimal?> = decimal(name, precision, scale).nullable()

@JvmName("columnJavaUuid")
fun <D> Table.column(
    property: KProperty1<D, UUID>,
    name: String = property.standardColumnName(),
): Column<UUID> = javaUUID(name)

@JvmName("columnNullableJavaUuid")
fun <D> Table.column(
    property: KProperty1<D, UUID?>,
    name: String = property.standardColumnName(),
): Column<UUID?> = javaUUID(name).nullable()

@JvmName("columnBinary")
fun <D> Table.column(
    property: KProperty1<D, ByteArray>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
    maxSize: Int? = null,
): Column<ByteArray> = configureBinaryColumn(name, encrypted, maxSize)

@JvmName("columnNullableBinary")
fun <D> Table.column(
    property: KProperty1<D, ByteArray?>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
    maxSize: Int? = null,
): Column<ByteArray?> = configureBinaryColumn(name, encrypted, maxSize).nullable()

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
): Column<JsonValue> = jsonValue(name)

@JvmName("columnNullableJsonValue")
fun <D> Table.column(
    property: KProperty1<D, JsonValue?>,
    name: String = property.standardColumnName(),
): Column<JsonValue?> = jsonValue(name).nullable()

@JvmName("columnJsonObject")
fun <D> Table.column(
    property: KProperty1<D, JsonObject>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<JsonObject> = if (encrypted) encryptedJsonObject(name) else jsonObject(name)

@JvmName("columnNullableJsonObject")
fun <D> Table.column(
    property: KProperty1<D, JsonObject?>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<JsonObject?> = (if (encrypted) encryptedJsonObject(name) else jsonObject(name)).nullable()

@JvmName("columnJsonArray")
fun <D> Table.column(
    property: KProperty1<D, JsonArray>,
    name: String = property.standardColumnName(),
): Column<JsonArray> = jsonArray(name)

@JvmName("columnNullableJsonArray")
fun <D> Table.column(
    property: KProperty1<D, JsonArray?>,
    name: String = property.standardColumnName(),
): Column<JsonArray?> = jsonArray(name).nullable()

@JvmName("columnDataEnum")
inline fun <D, reified E> Table.column(
    property: KProperty1<D, E>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<E> where E : Enum<E>, E : DataEnum =
    if (encrypted) encryptedDataEnum(name) else dataEnum(name)

@JvmName("columnNullableDataEnum")
inline fun <D, reified E> Table.column(
    property: KProperty1<D, E?>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<E?> where E : Enum<E>, E : DataEnum =
    (if (encrypted) encryptedDataEnum<E>(name) else dataEnum<E>(name)).nullable()

@JvmName("columnStringList")
fun <D> Table.column(
    property: KProperty1<D, List<String>>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<List<String>> = if (encrypted) encryptedStringList(name) else array<String>(name)

@JvmName("columnNullableStringList")
fun <D> Table.column(
    property: KProperty1<D, List<String>?>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<List<String>?> = (if (encrypted) encryptedStringList(name) else array<String>(name)).nullable()

@JvmName("columnDataEnumList")
inline fun <D, reified E> Table.column(
    property: KProperty1<D, List<E>>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<List<E>> where E : Enum<E>, E : DataEnum =
    if (encrypted) encryptedDataEnumList(name) else dataEnumList(name)

@JvmName("columnNullableDataEnumList")
inline fun <D, reified E> Table.column(
    property: KProperty1<D, List<E>?>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<List<E>?> where E : Enum<E>, E : DataEnum =
    (if (encrypted) encryptedDataEnumList<E>(name) else dataEnumList<E>(name)).nullable()

@JvmName("columnDataEnumSet")
inline fun <D, reified E> Table.column(
    property: KProperty1<D, Set<E>>,
    name: String = property.standardColumnName(),
): Column<Set<E>> where E : Enum<E>, E : DataEnum = dataEnumSet(name)

@JvmName("columnNullableDataEnumSet")
inline fun <D, reified E> Table.column(
    property: KProperty1<D, Set<E>?>,
    name: String = property.standardColumnName(),
): Column<Set<E>?> where E : Enum<E>, E : DataEnum = dataEnumSet<E>(name).nullable()

@JvmName("columnSet")
inline fun <D, reified E : Any> Table.column(
    property: KProperty1<D, Set<E>>,
    name: String = property.standardColumnName(),
): Column<Set<E>> = setArray(name)

@JvmName("columnNullableSet")
inline fun <D, reified E : Any> Table.column(
    property: KProperty1<D, Set<E>?>,
    name: String = property.standardColumnName(),
): Column<Set<E>?> = setArray<E>(name).nullable()

@JvmName("columnJsonObjectValue")
fun <D, T> Table.column(
    property: KProperty1<D, T>,
    fromJson: FromJsonObject<T>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<T> where T : Any, T : ToJsonObject =
    if (encrypted) encryptedJsonb(name, fromJson) else toFromJson(name, fromJson)

@JvmName("columnNullableJsonObjectValue")
fun <D, T> Table.column(
    property: KProperty1<D, T?>,
    fromJson: FromJsonObject<T>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<T?> where T : Any, T : ToJsonObject =
    (if (encrypted) encryptedJsonb(name, fromJson) else toFromJson(name, fromJson)).nullable()

@JvmName("columnJsonObjectValueList")
fun <D, T> Table.column(
    property: KProperty1<D, List<T>>,
    fromJson: FromJsonObject<T>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<List<T>> where T : Any, T : ToJsonObject =
    if (encrypted) encryptedJsonbList(name, fromJson) else toFromJsonArray(name, fromJson)

@JvmName("columnNullableJsonObjectValueList")
fun <D, T> Table.column(
    property: KProperty1<D, List<T>?>,
    fromJson: FromJsonObject<T>,
    name: String = property.standardColumnName(),
    encrypted: Boolean = false,
): Column<List<T>?> where T : Any, T : ToJsonObject =
    (if (encrypted) encryptedJsonbList(name, fromJson) else toFromJsonArray(name, fromJson)).nullable()

@JvmName("columnReference")
fun <D, T : Any> Table.column(
    property: KProperty1<D, T>,
    references: Column<T>,
    name: String = property.standardColumnName(),
    onDelete: ReferenceOption? = null,
    onUpdate: ReferenceOption? = null,
    foreignKeyName: String? = null,
): Column<T> = reference(name, references, onDelete, onUpdate, foreignKeyName)

@JvmName("columnNullableReference")
fun <D, T : Any> Table.column(
    property: KProperty1<D, T?>,
    references: Column<T>,
    name: String = property.standardColumnName(),
    onDelete: ReferenceOption? = null,
    onUpdate: ReferenceOption? = null,
    foreignKeyName: String? = null,
): Column<T?> = reference(name, references, onDelete, onUpdate, foreignKeyName).nullable()
