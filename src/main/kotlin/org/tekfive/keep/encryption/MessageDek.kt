package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead

/**
 * Envelope encryption for per-record Data Encryption Keys (DEKs).
 *
 * Each record gets a freshly generated AES-256-GCM Tink DEK. The DEK is wrapped
 * (encrypted) by a global Key Encryption Key (KEK) — the existing
 * [DatabaseEncryptionProvider.aead] — using Tink's native encrypted-keyset
 * serialization. Wrapped bytes are persisted; the cleartext DEK exists only in
 * process memory while it is in active use.
 *
 * Per-record destruction is deletion of the wrapped-DEK row: the persisted
 * ciphertext for that record becomes cryptographically inaccessible at that
 * instant. The compliance basis is NIST SP 800-88 Rev. 1 (key destruction is a
 * recognized "purge"-level destruction for encrypted media).
 *
 * The KEK never wraps record bytes directly. The DEK never wraps another DEK.
 */
class MessageDek internal constructor(private val aead: Aead) {
    /**
     * Encrypts [plaintext] under this DEK, binding [associatedData] into the
     * AEAD tag. The same [associatedData] must be supplied to [decrypt] or the
     * call throws.
     *
     * For message-item ciphertexts the convention is
     * `"msg:{messageId}:item:{ordinal}".toByteArray()` — this prevents an item
     * ciphertext from being meaningfully reused under a different message id
     * or ordinal.
     */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        aead.encrypt(plaintext, associatedData)

    /**
     * Decrypts [ciphertext] under this DEK, requiring the same [associatedData]
     * supplied at encryption time.
     */
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
        aead.decrypt(ciphertext, associatedData)
}
