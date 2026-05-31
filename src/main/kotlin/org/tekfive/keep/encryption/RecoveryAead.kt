package org.tekfive.keep.encryption

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.charset.StandardCharsets

/**
 * Thrown when ChaCha20-Poly1305 authentication fails on unwrap — either the
 * passphrase is wrong or the ciphertext (or salt/nonce/params) has been
 * tampered with. Treat the two cases as indistinguishable: do not leak which.
 */
class RecoveryAuthException : Exception("recovery authentication failed (wrong passphrase or tampered file)")

object RecoveryAead {

    private const val KEY_LEN = 32
    private const val TAG_BITS = 128  // 16-byte Poly1305 tag

    /**
     * Encrypt `plaintext` with a key derived from `passphrase` + `salt` via
     * Argon2id, using ChaCha20-Poly1305 over `nonce`. Returns ciphertext
     * concatenated with the 16-byte auth tag.
     */
    fun wrap(
        plaintext: ByteArray,
        passphrase: CharArray,
        salt: ByteArray,
        nonce: ByteArray,
        params: RecoveryArgon2Params,
    ): ByteArray {
        require(salt.size == 16)  { "salt must be 16 bytes" }
        require(nonce.size == 12) { "nonce must be 12 bytes" }

        val key = deriveKey(passphrase, salt, params)
        try {
            val cipher = ChaCha20Poly1305()
            cipher.init(true, AEADParameters(KeyParameter(key), TAG_BITS, nonce))
            val out = ByteArray(cipher.getOutputSize(plaintext.size))
            val n = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
            cipher.doFinal(out, n)
            return out
        } finally {
            java.util.Arrays.fill(key, 0)
        }
    }

    /**
     * Decrypt + verify `ciphertext` (containing a trailing 16-byte tag) under
     * the same key + nonce. Throws [RecoveryAuthException] on any auth failure.
     */
    fun unwrap(
        ciphertext: ByteArray,
        passphrase: CharArray,
        salt: ByteArray,
        nonce: ByteArray,
        params: RecoveryArgon2Params,
    ): ByteArray {
        require(salt.size == 16)
        require(nonce.size == 12)

        val key = deriveKey(passphrase, salt, params)
        try {
            val cipher = ChaCha20Poly1305()
            cipher.init(false, AEADParameters(KeyParameter(key), TAG_BITS, nonce))
            val out = ByteArray(cipher.getOutputSize(ciphertext.size))
            val n = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            val total = n + cipher.doFinal(out, n)
            return out.copyOfRange(0, total)
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw RecoveryAuthException()
        } finally {
            java.util.Arrays.fill(key, 0)
        }
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        params: RecoveryArgon2Params,
    ): ByteArray {
        val argonParams = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withMemoryAsKB(params.memoryKib)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .withSalt(salt)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(argonParams)
        val key = ByteArray(KEY_LEN)
        val pwBytes = encodeUtf8(passphrase)
        try {
            gen.generateBytes(pwBytes, key)
            return key
        } finally {
            java.util.Arrays.fill(pwBytes, 0)
        }
    }

    private fun encodeUtf8(chars: CharArray): ByteArray {
        val cb = java.nio.CharBuffer.wrap(chars)
        val bb = StandardCharsets.UTF_8.encode(cb)
        val out = ByteArray(bb.remaining())
        bb.get(out)
        // Best-effort zero of the encoder's backing array.
        if (bb.hasArray()) java.util.Arrays.fill(bb.array(), 0)
        return out
    }
}
