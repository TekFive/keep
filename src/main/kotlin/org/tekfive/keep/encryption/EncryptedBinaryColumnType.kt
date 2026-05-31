package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed column type that stores a [ByteArray] as Tink AEAD-encrypted BYTEA.
 */
class EncryptedBinaryColumnType(
    associatedData: String,
    aead: Aead = DatabaseEncryptionProvider.aead,
) : EncryptedColumnType<ByteArray>(associatedData, aead) {

    override fun serialize(value: ByteArray): ByteArray = value
    override fun deserialize(plaintext: ByteArray): ByteArray = plaintext
}

/** Registers a BYTEA column that stores a [ByteArray] encrypted with Tink AEAD. */
fun Table.encryptedBinary(name: String): Column<ByteArray> =
    registerColumn(name, EncryptedBinaryColumnType("${tableName}.$name"))
