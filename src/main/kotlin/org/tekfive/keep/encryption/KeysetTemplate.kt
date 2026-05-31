package org.tekfive.keep.encryption

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters

/**
 * Single source of truth for the keyset shape this tool produces.
 *
 * The corresponding consumer is `DatabaseEncryptionProvider` in `libs/keep`,
 * which loads the keyset via `TinkJsonProtoKeysetFormat.parseKeyset` and
 * unwraps DEKs with the resulting AEAD primitive. Both sides must agree
 * on the parameters used to generate the primary key.
 */
object KeysetTemplate {
    const val TEMPLATE_NAME = "AES256_GCM"

    private val PARAMETERS = PredefinedAeadParameters.AES256_GCM
    private var registered = false

    /**
     * Registers Tink's AEAD config. Safe to call multiple times.
     */
    @Synchronized
    fun register() {
        if (registered) return
        AeadConfig.register()
        registered = true
    }

    /**
     * Generates a fresh keyset handle containing one primary AES-256-GCM key.
     */
    fun generateNewKeysetHandle(): KeysetHandle {
        register()
        return KeysetHandle.generateNew(PARAMETERS)
    }
}
