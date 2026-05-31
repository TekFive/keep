package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.asRequiredJsonObject

/**
 * Exposed column type that stores a [ToJsonObject]/[FromJsonObject]-serializable value as
 * Tink AEAD-encrypted BYTEA.
 */
class EncryptedToJsonObjectColumnType<T>(
    private val fromJson: FromJsonObject<T>,
    associatedData: String,
    aead: Aead = DatabaseEncryptionProvider.aead,
) : EncryptedColumnType<T>(associatedData, aead) where T : Any, T : ToJsonObject {

    override fun serialize(value: T): ByteArray =
        value.toJsonString().toByteArray(Charsets.UTF_8)

    override fun deserialize(plaintext: ByteArray): T {
        val json = String(plaintext, Charsets.UTF_8)
        return fromJson.fromJson(json.asRequiredJsonObject())
    }
}

/** Registers a BYTEA column that stores [ToJsonObject]/[FromJsonObject]-serialized data encrypted with Tink AEAD. */
fun <T> Table.encryptedJsonb(name: String, fromJson: FromJsonObject<T>): Column<T> where T : Any, T : ToJsonObject =
    registerColumn(name, EncryptedToJsonObjectColumnType(fromJson, "${tableName}.$name"))
