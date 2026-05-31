package org.tekfive.keep.encryption

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseEncryptionProviderTest {

    @BeforeTest
    fun reset() {
        AeadConfig.register()
        DatabaseEncryptionProvider.resetForTesting()
    }

    @Test
    fun `initializes from a plaintext file path`(@TempDir tmp: Path) {
        val path = tmp.resolve("keyset.json")
        KeysetIO.write(KeysetTemplate.generateNewKeysetHandle(), path)

        DatabaseEncryptionProvider.configure(
            KeysetLoader.Config(
                mode = EncryptionKeysetMode.PLAINTEXT,
                file = path,
            )
        )
        DatabaseEncryptionProvider.ensureInitialized()
        assertNotNull(DatabaseEncryptionProvider.aead)
    }

    @Test
    fun `auto-generates a plaintext keyset when configured`(@TempDir tmp: Path) {
        val path = tmp.resolve("absent.json")
        DatabaseEncryptionProvider.configure(
            KeysetLoader.Config(
                mode = EncryptionKeysetMode.PLAINTEXT,
                file = path,
                autoGenerate = true,
            )
        )
        DatabaseEncryptionProvider.ensureInitialized()
        assertNotNull(DatabaseEncryptionProvider.aead)
    }

    @Test
    fun `fails fast when nothing is configured`() {
        val ex = assertFailsWith<IllegalStateException> {
            DatabaseEncryptionProvider.ensureInitialized()
        }
        assert(ex.message!!.contains("configure"))
    }

    @Test
    fun `recovery passphrase is zeroed after ensureInitialized`(@TempDir tmp: Path) {
        val recoveryPath = tmp.resolve("keyset.recovery")
        val passphraseForFile = "right-passphrase".toCharArray()

        val source = KeysetTemplate.generateNewKeysetHandle()
        val json = TinkJsonProtoKeysetFormat.serializeKeyset(source, InsecureSecretKeyAccess.get())
        val rng = SecureRandom()
        val salt  = ByteArray(16).also { rng.nextBytes(it) }
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val ciphertext = RecoveryAead.wrap(json.toByteArray(Charsets.UTF_8), passphraseForFile, salt, nonce, TestArgon2Params.fast)
        RecoveryFile.write(recoveryPath, TestArgon2Params.fast, salt, nonce, ciphertext)

        val handedToConfig = "right-passphrase".toCharArray()
        DatabaseEncryptionProvider.configure(
            KeysetLoader.Config(
                mode = EncryptionKeysetMode.RECOVERY,
                file = recoveryPath,
                recoveryPassphrase = handedToConfig,
            )
        )
        DatabaseEncryptionProvider.ensureInitialized()

        assertTrue(
            handedToConfig.all { it == ' ' },
            "passphrase CharArray should be zeroed after ensureInitialized; was: ${String(handedToConfig)}"
        )
    }
}
