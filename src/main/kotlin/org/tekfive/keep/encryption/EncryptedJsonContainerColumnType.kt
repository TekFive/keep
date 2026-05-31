package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.jfk.Json
import org.tekfive.jfk.JsonContainer

/**
 * Exposed column type that stores a [JsonContainer] (object or array) as Tink AEAD-encrypted BYTEA.
 */
class EncryptedJsonContainerColumnType(
    associatedData: String,
    aead: Aead = DatabaseEncryptionProvider.aead,
) : EncryptedColumnType<JsonContainer>(associatedData, aead) {

    override fun serialize(value: JsonContainer): ByteArray =
        value.toJsonString().toByteArray(Charsets.UTF_8)

    override fun deserialize(plaintext: ByteArray): JsonContainer {
        val parsed = Json.parse(String(plaintext, Charsets.UTF_8))
        return (parsed.obj ?: parsed.array) as JsonContainer
    }
}

/** Registers a BYTEA column that stores a [JsonContainer] (object or array) encrypted with Tink AEAD. */
fun Table.encryptedJsonContainer(name: String): Column<JsonContainer> =
    registerColumn(name, EncryptedJsonContainerColumnType("${tableName}.$name"))
