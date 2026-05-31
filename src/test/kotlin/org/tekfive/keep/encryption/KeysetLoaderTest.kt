package org.tekfive.keep.encryption

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KeysetLoaderTest {
    @BeforeTest fun setup() { AeadConfig.register() }

    @Test
    fun `plaintext mode reads an existing keyset file`(@TempDir tmp: Path) {
        val path = tmp.resolve("keyset.json")
        KeysetIO.write(KeysetTemplate.generateNewKeysetHandle(), path)

        val handle = KeysetLoader.load(
            KeysetLoader.Config(
                mode = EncryptionKeysetMode.PLAINTEXT,
                file = path,
                autoGenerate = false,
            )
        )
        assertNotNull(handle)
    }

    @Test
    fun `plaintext mode with auto-generate writes a new keyset when file is missing`(@TempDir tmp: Path) {
        val path = tmp.resolve("nope.json")
        assertTrue(!path.exists())

        val handle = KeysetLoader.load(
            KeysetLoader.Config(
                mode = EncryptionKeysetMode.PLAINTEXT,
                file = path,
                autoGenerate = true,
            )
        )
        assertNotNull(handle)
        assertTrue(path.exists())
        assertTrue(path.readText().startsWith("{"))
    }

    @Test
    fun `plaintext mode without auto-generate fails when file is missing`(@TempDir tmp: Path) {
        val ex = assertFailsWith<KeysetIOException> {
            KeysetLoader.load(
                KeysetLoader.Config(
                    mode = EncryptionKeysetMode.PLAINTEXT,
                    file = tmp.resolve("nope.json"),
                    autoGenerate = false,
                )
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test
    @DisabledOnOs(OS.MAC, disabledReason = "ClevisMock uses GNU base64 -w0, which is not supported by BSD base64 on macOS")
    fun `sealed mode unseals via the supplied ClevisRunner`(@TempDir tmp: Path) {
        val sealedPath = tmp.resolve("keyset.sealed")
        ClevisMock.install(tmp)
        val clevis = ClevisRunner(pathOverride = ClevisMock.pathWithMockClevis(tmp))

        // Set up: serialize keyset to JSON, encrypt with clevis, store as SealedFile
        val source = KeysetTemplate.generateNewKeysetHandle()
        val json = TinkJsonProtoKeysetFormat.serializeKeyset(source, InsecureSecretKeyAccess.get())
        val jwe = clevis.encrypt(json.toByteArray(Charsets.UTF_8))
        SealedFile.write(sealedPath, jwe)

        val handle = KeysetLoader.load(
            KeysetLoader.Config(
                mode = EncryptionKeysetMode.SEALED,
                file = sealedPath,
                autoGenerate = false,
                clevisRunner = clevis,
            )
        )
        assertNotNull(handle)
    }

    private fun createRecoveryFile(path: Path, passphrase: CharArray, params: RecoveryArgon2Params = TestArgon2Params.fast) {
        val source = KeysetTemplate.generateNewKeysetHandle()
        val json = TinkJsonProtoKeysetFormat.serializeKeyset(source, InsecureSecretKeyAccess.get())
        val rng = SecureRandom()
        val salt  = ByteArray(16).also { rng.nextBytes(it) }
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val ciphertext = RecoveryAead.wrap(json.toByteArray(Charsets.UTF_8), passphrase, salt, nonce, params)
        RecoveryFile.write(path, params, salt, nonce, ciphertext)
    }

    @Test
    fun `recovery mode unwraps via the supplied passphrase`(@TempDir tmp: Path) {
        val recoveryPath = tmp.resolve("keyset.recovery")
        val passphrase = "test-passphrase".toCharArray()

        createRecoveryFile(recoveryPath, passphrase)

        val handle = KeysetLoader.load(
            KeysetLoader.Config(
                mode = EncryptionKeysetMode.RECOVERY,
                file = recoveryPath,
                autoGenerate = false,
                recoveryPassphrase = passphrase,
            )
        )
        assertNotNull(handle)
    }

    @Test
    fun `recovery mode rejects wrong passphrase with KeysetIOException`(@TempDir tmp: Path) {
        val recoveryPath = tmp.resolve("keyset.recovery")
        val rightPassphrase = "right".toCharArray()
        val wrongPassphrase = "wrong".toCharArray()

        createRecoveryFile(recoveryPath, rightPassphrase)

        val ex = assertFailsWith<KeysetIOException> {
            KeysetLoader.load(
                KeysetLoader.Config(
                    mode = EncryptionKeysetMode.RECOVERY,
                    file = recoveryPath,
                    autoGenerate = false,
                    recoveryPassphrase = wrongPassphrase,
                )
            )
        }
        assertTrue(ex.message!!.contains("recovery") || ex.message!!.contains("wrong passphrase"))
    }

    @Test
    fun `auto-generate is rejected in non-plaintext modes`(@TempDir tmp: Path) {
        val ex = assertFailsWith<IllegalArgumentException> {
            KeysetLoader.load(
                KeysetLoader.Config(
                    mode = EncryptionKeysetMode.SEALED,
                    file = tmp.resolve("x"),
                    autoGenerate = true,
                )
            )
        }
        assertTrue(ex.message!!.contains("auto-generate"))
    }

    @Test
    @DisabledOnOs(OS.MAC, disabledReason = "ClevisMock uses GNU base64 -w0, which is not supported by BSD base64 on macOS")
    fun `sealed mode wraps Tink parse failure in KeysetIOException`(@TempDir tmp: Path) {
        val sealedPath = tmp.resolve("keyset.sealed")
        ClevisMock.install(tmp)
        val clevis = ClevisRunner(pathOverride = ClevisMock.pathWithMockClevis(tmp))

        // Seal a payload that's NOT a Tink keyset — clevis decrypt will succeed
        // and hand the bytes to Tink, which then rejects them.
        val garbage = """{"this": "is not a Tink keyset"}""".toByteArray(Charsets.UTF_8)
        val jwe = clevis.encrypt(garbage)
        SealedFile.write(sealedPath, jwe)

        val ex = assertFailsWith<KeysetIOException> {
            KeysetLoader.load(
                KeysetLoader.Config(
                    mode = EncryptionKeysetMode.SEALED,
                    file = sealedPath,
                    clevisRunner = clevis,
                )
            )
        }
        assertTrue(
            ex.message!!.contains("sealed keyset") || ex.message!!.contains("parse"),
            "expected parse-failure message, got: ${ex.message}"
        )
    }
}
