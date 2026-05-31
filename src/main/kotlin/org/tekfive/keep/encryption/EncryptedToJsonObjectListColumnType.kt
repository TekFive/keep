package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.asRequiredJsonArray

/**
 * Exposed column type that stores a list of [ToJsonObject]/[FromJsonObject]-serializable values
 * as Tink AEAD-encrypted BYTEA. The list is serialized as a JSON array.
 */
class EncryptedToJsonObjectListColumnType<T>(
    private val fromJson: FromJsonObject<T>,
    associatedData: String,
    aead: Aead = DatabaseEncryptionProvider.aead,
) : EncryptedColumnType<List<T>>(associatedData, aead) where T : Any, T : ToJsonObject {

    override fun serialize(value: List<T>): ByteArray {
        val jsonArray = JsonArray(value.map { it.toJsonObject() })
        return jsonArray.toJsonString().toByteArray(Charsets.UTF_8)
    }

    override fun deserialize(plaintext: ByteArray): List<T> {
        val json = String(plaintext, Charsets.UTF_8)
        val array = json.asRequiredJsonArray()
        return array.items.map { fromJson.fromJson(it as JsonObject) }
    }
}

/** Registers a BYTEA column that stores a list of [ToJsonObject]/[FromJsonObject]-serialized data encrypted with Tink AEAD. */
fun <T> Table.encryptedJsonbList(name: String, fromJson: FromJsonObject<T>): Column<List<T>> where T : Any, T : ToJsonObject =
    registerColumn(name, EncryptedToJsonObjectListColumnType(fromJson, "${tableName}.$name"))
