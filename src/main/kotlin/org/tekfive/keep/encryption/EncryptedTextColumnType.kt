package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed column type that stores a [String] as Tink AEAD-encrypted BYTEA.
 */
class EncryptedTextColumnType(
    associatedData: String,
    aead: Aead = DatabaseEncryptionProvider.aead,
) : EncryptedColumnType<String>(associatedData, aead) {

    override fun serialize(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
    override fun deserialize(plaintext: ByteArray): String = String(plaintext, Charsets.UTF_8)
}

/** Registers a BYTEA column that stores a [String] encrypted with Tink AEAD. */
fun Table.encryptedText(name: String): Column<String> =
    registerColumn(name, EncryptedTextColumnType("${tableName}.$name"))
