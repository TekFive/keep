package org.tekfive.keep.encryption

enum class EncryptionKeysetMode {
    /** Cleartext Tink JSON keyset on disk. Dev only. */
    PLAINTEXT,

    /** [SealedFile] format, unsealable via [ClevisRunner] against the host TPM. */
    SEALED,

    /** [RecoveryFile] format, unwrapped via the recovery passphrase. */
    RECOVERY;

    companion object {
        fun parse(raw: String): EncryptionKeysetMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown ENCRYPTION_KEYSET_MODE '$raw'; expected one of " +
                            entries.joinToString { it.name }
                )
    }
}
