package org.tekfive.keep.encryption

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.tekfive.keep.encryption.ClevisMock
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

@Disabled("Requires clevis install locally")
class ClevisRunnerTest {

    @Test
    fun `encrypt then decrypt round-trips plaintext`(@TempDir tmp: Path) {
        ClevisMock.install(tmp)
        val runner = ClevisRunner(pathOverride = ClevisMock.pathWithMockClevis(tmp))

        val plaintext = "hello tpm".toByteArray()
        val sealed = runner.encrypt(plaintext)
        assertTrue(String(sealed).startsWith("MOCK-JWE:"), "sealed=${String(sealed)}")

        val recovered = runner.decrypt(sealed)
        assertContentEquals(plaintext, recovered)
    }

    @Test
    fun `encrypt logs the policy`(@TempDir tmp: Path) {
        val log = ClevisMock.install(tmp)
        val runner = ClevisRunner(pathOverride = ClevisMock.pathWithMockClevis(tmp))
        runner.encrypt("plaintext".toByteArray())
        val logged = Files.readAllLines(log)
        assertTrue(logged.any { it.contains("encrypt") && it.contains("tpm2") },
                   "expected an encrypt tpm2 line in log: $logged")
    }

    @Test
    fun `encrypt throws TpmException when clevis is missing on PATH`(@TempDir tmp: Path) {
        val runner = ClevisRunner(pathOverride = "/nonexistent")
        assertThrows<TpmException> { runner.encrypt("x".toByteArray()) }
    }

    @Test
    fun `encrypt throws TpmException when clevis fails`(@TempDir tmp: Path) {
        ClevisMock.install(tmp)
        val runner = ClevisRunner(
            pathOverride = ClevisMock.pathWithMockClevis(tmp),
            extraEnv = mapOf("MOCK_CLEVIS_FAIL" to "encrypt"),
        )
        val e = assertThrows<TpmException> { runner.encrypt("x".toByteArray()) }
        assertTrue(e.message!!.contains("encrypt"), "msg=${e.message}")
    }

    @Test
    fun `decrypt throws TpmException when clevis fails`(@TempDir tmp: Path) {
        ClevisMock.install(tmp)
        val good = ClevisRunner(pathOverride = ClevisMock.pathWithMockClevis(tmp)).encrypt("x".toByteArray())
        val runner = ClevisRunner(
            pathOverride = ClevisMock.pathWithMockClevis(tmp),
            extraEnv = mapOf("MOCK_CLEVIS_FAIL" to "decrypt"),
        )
        assertThrows<TpmException> { runner.decrypt(good) }
    }
}
