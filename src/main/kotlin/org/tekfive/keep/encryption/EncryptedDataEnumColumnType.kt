package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.asRequiredJsonArray
import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.DataEnumType

/**
 * Exposed column type that stores a [DataEnum] enum as Tink AEAD-encrypted BYTEA.
 *
 * The plaintext payload is the enum's stable [DataEnum.id], not the enum ordinal.
 */
class EncryptedDataEnumColumnType<E>(
    values: Array<E>,
    associatedData: String,
    aead: Aead = DatabaseEncryptionProvider.aead,
) : EncryptedColumnType<E>(associatedData, aead), DataEnumType<E> where E : Enum<E>, E : DataEnum {

    override val dataEnumValues: Array<E> = values

    init {
        DataEnum.validate(dataEnumValues.toList())
    }

    override fun serialize(value: E): ByteArray =
        value.id.toString().toByteArray(Charsets.UTF_8)

    override fun deserialize(plaintext: ByteArray): E {
        val id = String(plaintext, Charsets.UTF_8).toInt()
        return map(id)
    }
}

/**
 * Exposed column type that stores a list of [DataEnum] enums as Tink AEAD-encrypted BYTEA.
 *
 * The plaintext payload is a JSON array of stable [DataEnum.id] values, not enum ordinals.
 */
class EncryptedDataEnumListColumnType<E>(
    values: Array<E>,
    associatedData: String,
    aead: Aead = DatabaseEncryptionProvider.aead,
) : EncryptedColumnType<List<E>>(associatedData, aead), DataEnumType<E> where E : Enum<E>, E : DataEnum {

    override val dataEnumValues: Array<E> = values

    init {
        DataEnum.validate(dataEnumValues.toList())
    }

    override fun serialize(value: List<E>): ByteArray =
        JsonArray(value.map { it.id }).toJsonString().toByteArray(Charsets.UTF_8)

    override fun deserialize(plaintext: ByteArray): List<E> {
        val json = String(plaintext, Charsets.UTF_8)
        return json.asRequiredJsonArray()
            .toReqIntList()
            .map { map(it) }
    }
}

/** Registers a BYTEA column that stores a [DataEnum] enum encrypted with Tink AEAD. */
inline fun <reified E> Table.encryptedDataEnum(name: String): Column<E> where E : Enum<E>, E : DataEnum =
    registerColumn(name, EncryptedDataEnumColumnType(enumValues<E>(), "${tableName}.$name"))

/** Registers a BYTEA column that stores a list of [DataEnum] enums encrypted with Tink AEAD. */
inline fun <reified E> Table.encryptedDataEnumList(name: String): Column<List<E>> where E : Enum<E>, E : DataEnum =
    registerColumn(name, EncryptedDataEnumListColumnType(enumValues<E>(), "${tableName}.$name"))
