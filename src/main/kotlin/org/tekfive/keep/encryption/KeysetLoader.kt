package org.tekfive.keep.encryption

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.exists

object KeysetLoader {

    data class Config(
        val mode: EncryptionKeysetMode,
        val file: Path,
        val autoGenerate: Boolean = false,
        val recoveryPassphrase: CharArray? = null,
        /** Injected for tests; defaults to a real Clevis shell-out. */
        val clevisRunner: ClevisRunner = ClevisRunner(),
    ) {
        init {
            require(!(autoGenerate && mode != EncryptionKeysetMode.PLAINTEXT)) {
                "auto-generate is only valid in PLAINTEXT mode (got $mode)"
            }
            if (mode == EncryptionKeysetMode.RECOVERY) {
                require(recoveryPassphrase != null) {
                    "recoveryPassphrase is required in RECOVERY mode"
                }
            }
        }
    }

    /** Loads the keyset described by [config]. Honors auto-generate in PLAINTEXT mode. */
    fun load(config: Config): KeysetHandle = when (config.mode) {
        EncryptionKeysetMode.PLAINTEXT -> loadPlaintext(config)
        EncryptionKeysetMode.SEALED    -> loadSealed(config)
        EncryptionKeysetMode.RECOVERY  -> loadRecovery(config)
    }

    private fun loadPlaintext(config: Config): KeysetHandle {
        // No byte-array to zero in PLAINTEXT mode — the disk file is the persistent
        // copy, so zeroing the in-memory bytes would not change the threat model.
        if (config.file.exists()) {
            return KeysetIO.read(config.file)
        }
        if (!config.autoGenerate) {
            throw KeysetIOException("keyset file not found at ${config.file} (set ENCRYPTION_KEYSET_AUTO_GENERATE=true to generate one)")
        }
        val fresh = KeysetTemplate.generateNewKeysetHandle()
        KeysetIO.write(fresh, config.file)
        return fresh
    }

    private fun loadSealed(config: Config): KeysetHandle {
        val jwe = SealedFile.read(config.file)
        val plaintext = config.clevisRunner.decrypt(jwe)
        return try {
            KeysetTemplate.register()
            // Tink's parseKeyset only accepts String; this transient copy of the cleartext
            // keyset JSON lives in heap until GC and cannot be zeroed. The byte array is
            // zeroed in finally; the String is the bounded residual.
            TinkJsonProtoKeysetFormat.parseKeyset(
                plaintext.toString(Charsets.UTF_8),
                InsecureSecretKeyAccess.get()
            )
        } catch (t: Throwable) {
            throw KeysetIOException("failed to parse sealed keyset from ${config.file}: ${t.message}", t)
        } finally {
            java.util.Arrays.fill(plaintext, 0)
        }
    }

    private fun loadRecovery(config: Config): KeysetHandle {
        val contents = RecoveryFile.read(config.file)
        val passphrase = config.recoveryPassphrase!!
        val plaintext = try {
            RecoveryAead.unwrap(contents.ciphertext, passphrase, contents.salt, contents.nonce, contents.params)
        } catch (e: RecoveryAuthException) {
            throw KeysetIOException("recovery authentication failed for ${config.file}: wrong passphrase or tampered file", e)
        }
        return try {
            KeysetTemplate.register()
            // Tink's parseKeyset only accepts String; this transient copy of the cleartext
            // keyset JSON lives in heap until GC and cannot be zeroed. The byte array is
            // zeroed in finally; the String is the bounded residual.
            TinkJsonProtoKeysetFormat.parseKeyset(
                plaintext.toString(Charsets.UTF_8),
                InsecureSecretKeyAccess.get()
            )
        } catch (t: Throwable) {
            throw KeysetIOException("failed to parse recovered keyset from ${config.file}: ${t.message}", t)
        } finally {
            java.util.Arrays.fill(plaintext, 0)
        }
    }
}
