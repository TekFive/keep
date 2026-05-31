package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.jfk.Json
import org.tekfive.jfk.JsonObject

/**
 * Exposed column type that stores a [JsonObject] as Tink AEAD-encrypted BYTEA.
 */
class EncryptedJsonObjectColumnType(
    associatedData: String,
    aead: Aead = DatabaseEncryptionProvider.aead,
) : EncryptedColumnType<JsonObject>(associatedData, aead) {

    override fun serialize(value: JsonObject): ByteArray =
        value.toJsonString().toByteArray(Charsets.UTF_8)

    override fun deserialize(plaintext: ByteArray): JsonObject =
        Json.parse(String(plaintext, Charsets.UTF_8)).reqObj
}

/** Registers a BYTEA column that stores a [JsonObject] encrypted with Tink AEAD. */
fun Table.encryptedJsonObject(name: String): Column<JsonObject> =
    registerColumn(name, EncryptedJsonObjectColumnType("${tableName}.$name"))
