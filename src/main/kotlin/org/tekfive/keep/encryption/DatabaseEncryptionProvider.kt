package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import org.slf4j.LoggerFactory

object DatabaseEncryptionProvider {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var config: KeysetLoader.Config? = null
    @Volatile private var handle: KeysetHandle? = null
    @Volatile private var cachedAead: Aead? = null

    /**
     * Call once during application bootstrap with the configured keyset
     * source. Subsequent calls before [resetForTesting] are ignored.
     */
    fun configure(config: KeysetLoader.Config) {
        if (this.config == null) {
            this.config = config
        }
    }

    /** Force re-initialization; tests only. */
    fun resetForTesting() {
        config = null
        handle = null
        cachedAead = null
    }

    val aead: Aead
        get() = checkNotNull(cachedAead) {
            "DatabaseEncryptionProvider has not been initialized; call ensureInitialized() during bootstrap"
        }

    /** Loads the keyset described by [configure]. Idempotent — safe to call repeatedly. */
    fun ensureInitialized() {
        if (handle != null) return
        synchronized(this) {
            if (handle != null) return
            val cfg = config
                ?: error("DatabaseEncryptionProvider has not been configure()d; cannot load keyset")

            AeadConfig.register()
            if (cfg.mode != EncryptionKeysetMode.SEALED) {
                log.warn(
                    "ENCRYPTION_KEYSET_MODE={} is not SEALED. Sealed mode is the only production-safe choice.",
                    cfg.mode
                )
            }
            if (cfg.mode == EncryptionKeysetMode.PLAINTEXT && cfg.autoGenerate) {
                log.warn(
                    "ENCRYPTION_KEYSET_AUTO_GENERATE=true — a fresh keyset will be created at {} if absent. " +
                        "Do NOT use this mode on a production appliance.",
                    cfg.file
                )
            }
            val loaded = KeysetLoader.load(cfg)
            // Defense-in-depth: the passphrase served its purpose; zero it so it does
            // not live in Config for the JVM's lifetime.
            cfg.recoveryPassphrase?.let { java.util.Arrays.fill(it, ' ') }
            cachedAead = loaded.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            handle = loaded
        }
    }
}
