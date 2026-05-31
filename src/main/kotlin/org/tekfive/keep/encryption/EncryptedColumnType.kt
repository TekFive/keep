package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import org.jetbrains.exposed.v1.core.ColumnType
import java.sql.Blob

/**
 * Abstract base for Exposed [ColumnType]s that store values as Tink AEAD-encrypted BYTEA.
 *
 * Subclasses provide [serialize] and [deserialize] to convert between [T] and [ByteArray].
 * All AEAD encryption/decryption, byte extraction from the database result, associated-data
 * binding, and equality semantics are handled here.
 *
 * The [associatedData] (typically `tableName.columnName`) is bound into the AEAD
 * authentication tag, preventing ciphertext from being moved between columns or tables
 * without detection.
 */
abstract class EncryptedColumnType<T : Any>(
    private val associatedData: String,
    private val aead: Aead = DatabaseEncryptionProvider.aead,
) : ColumnType<T>() {

    private val associatedDataBytes = associatedData.toByteArray(Charsets.UTF_8)

    override fun sqlType(): String = "BYTEA"

    /** Convert the application value to plaintext bytes for encryption. */
    protected abstract fun serialize(value: T): ByteArray

    /** Convert decrypted plaintext bytes back to the application value. */
    protected abstract fun deserialize(plaintext: ByteArray): T

    override fun notNullValueToDB(value: T): Any =
        aead.encrypt(serialize(value), associatedDataBytes)

    override fun valueFromDB(value: Any): T {
        val ciphertext = extractBytes(value)
        val plaintext = aead.decrypt(ciphertext, associatedDataBytes)
        return deserialize(plaintext)
    }

    override fun equals(other: Any?): Boolean =
        other != null && this::class == other::class && (other as EncryptedColumnType<*>).associatedData == associatedData

    override fun hashCode(): Int = associatedData.hashCode()

    companion object {
        fun extractBytes(value: Any): ByteArray = when (value) {
            is ByteArray -> value
            is Blob -> value.binaryStream.use { it.readBytes() }
            else -> error("Expected ByteArray from BYTEA column, got ${value::class}")
        }
    }
}
