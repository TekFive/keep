package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.asRequiredJsonArray

/**
 * Exposed column type that stores a [List] of [String]s as Tink AEAD-encrypted BYTEA.
 * The list is serialized as a JSON array of strings.
 */
class EncryptedStringListColumnType(
    associatedData: String,
    aead: Aead = DatabaseEncryptionProvider.aead,
) : EncryptedColumnType<List<String>>(associatedData, aead) {

    override fun serialize(value: List<String>): ByteArray {
        return JsonArray(value).toJsonString().toByteArray(Charsets.UTF_8)
    }

    override fun deserialize(plaintext: ByteArray): List<String> {
        val array = String(plaintext, Charsets.UTF_8).asRequiredJsonArray()
        return array.items.map { it.reqString }
    }
}

/** Registers a BYTEA column that stores a list of strings encrypted with Tink AEAD. */
fun Table.encryptedStringList(name: String): Column<List<String>> =
    registerColumn(name, EncryptedStringListColumnType("${tableName}.$name"))
