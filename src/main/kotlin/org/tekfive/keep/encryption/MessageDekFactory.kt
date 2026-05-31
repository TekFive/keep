package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters

/**
 * Generates new DEKs and wraps/unwraps them under a KEK. Stateless — safe to
 * share. Callers supply the KEK [Aead] (typically [DatabaseEncryptionProvider.aead])
 * at construction time.
 *
 * The wrap [associatedData] domain-separates wrapped DEKs from any other Tink
 * artifact that might be stored using the same KEK; the default value
 * `"message_dek"` is appropriate for the messaging system.
 */
class MessageDekFactory(
    private val kekAead: Aead,
    private val wrapAssociatedData: ByteArray = DEFAULT_WRAP_AD,
) {
    init {
        AeadConfig.register()
    }

    /**
     * Generates a fresh AES-256-GCM DEK and returns both the in-memory DEK
     * (for immediate use by the caller) and its wrapped bytes (for persistence).
     */
    fun generate(): GeneratedDek {
        val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        val wrapped = TinkProtoKeysetFormat.serializeEncryptedKeyset(handle, kekAead, wrapAssociatedData)
        return GeneratedDek(MessageDek(handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)), wrapped)
    }

    /**
     * Unwraps a previously persisted DEK so its [MessageDek] can be used to
     * decrypt records. Throws if the wrapped bytes do not authenticate against
     * the KEK with the configured associated data — this guards against
     * substituting a wrapped-key blob from a different domain.
     */
    fun unwrap(wrappedDek: ByteArray): MessageDek {
        val handle = TinkProtoKeysetFormat.parseEncryptedKeyset(wrappedDek, kekAead, wrapAssociatedData)
        return MessageDek(handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java))
    }

    companion object {
        val DEFAULT_WRAP_AD: ByteArray = "message_dek".toByteArray()
    }
}
