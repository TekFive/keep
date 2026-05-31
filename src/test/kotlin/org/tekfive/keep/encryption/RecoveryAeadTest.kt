package org.tekfive.keep.encryption

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RecoveryAeadTest {

    private val testParams = RecoveryArgon2Params(memoryKib = 8, iterations = 1, parallelism = 1)
    private val salt = ByteArray(16) { it.toByte() }
    private val nonce = ByteArray(12) { (it + 100).toByte() }
    private val passphrase = "correct horse battery staple".toCharArray()

    @Test
    fun `wrap then unwrap returns the original plaintext`() {
        val plaintext = "the rain in spain".toByteArray()
        val ciphertext = RecoveryAead.wrap(plaintext, passphrase, salt, nonce, testParams)
        val recovered = RecoveryAead.unwrap(ciphertext, passphrase, salt, nonce, testParams)
        assertContentEquals(plaintext, recovered)
    }

    @Test
    fun `ciphertext differs from plaintext`() {
        val plaintext = "the rain in spain".toByteArray()
        val ciphertext = RecoveryAead.wrap(plaintext, passphrase, salt, nonce, testParams)
        assertEquals(false, plaintext.contentEquals(ciphertext.copyOfRange(0, plaintext.size)))
    }

    @Test
    fun `wrong passphrase fails authentication on unwrap`() {
        val plaintext = "secret".toByteArray()
        val ciphertext = RecoveryAead.wrap(plaintext, passphrase, salt, nonce, testParams)
        assertThrows<RecoveryAuthException> {
            RecoveryAead.unwrap(ciphertext, "wrong-passphrase".toCharArray(), salt, nonce, testParams)
        }
    }

    @Test
    fun `tampered ciphertext fails authentication on unwrap`() {
        val plaintext = "secret".toByteArray()
        val ciphertext = RecoveryAead.wrap(plaintext, passphrase, salt, nonce, testParams)
        ciphertext[0] = (ciphertext[0].toInt() xor 0xFF).toByte()
        assertThrows<RecoveryAuthException> {
            RecoveryAead.unwrap(ciphertext, passphrase, salt, nonce, testParams)
        }
    }

    @Test
    fun `different salt produces different ciphertext`() {
        val plaintext = "same input".toByteArray()
        val salt2 = ByteArray(16) { (it + 50).toByte() }
        val ct1 = RecoveryAead.wrap(plaintext, passphrase, salt,  nonce, testParams)
        val ct2 = RecoveryAead.wrap(plaintext, passphrase, salt2, nonce, testParams)
        assertEquals(false, ct1.contentEquals(ct2))
    }

    @Test
    fun `output length is plaintext length plus 16-byte tag`() {
        val plaintext = "the rain in spain".toByteArray()
        val ciphertext = RecoveryAead.wrap(plaintext, passphrase, salt, nonce, testParams)
        assertEquals(plaintext.size + 16, ciphertext.size)
    }
}
