package org.tekfive.keep.encryption

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists

class RecoveryFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class RecoveryFileContents(
    val params: RecoveryArgon2Params,
    val salt: ByteArray,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecoveryFileContents) return false
        return params == other.params
            && salt.contentEquals(other.salt)
            && nonce.contentEquals(other.nonce)
            && ciphertext.contentEquals(other.ciphertext)
    }
    override fun hashCode(): Int =
        31 * (31 * (31 * params.hashCode() + salt.contentHashCode()) + nonce.contentHashCode()) + ciphertext.contentHashCode()
}

object RecoveryFile {

    private val MAGIC = byteArrayOf(
        'A'.code.toByte(), 'I'.code.toByte(), 'D'.code.toByte(),
        'R'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte(),
    )
    private const val FORMAT_VERSION: Byte = 0x01
    private const val MAGIC_TRAILER: Byte = '\n'.code.toByte()
    private const val HEADER_LEN = 48
    private const val MIN_CIPHERTEXT_LEN = 16

    /** Maximum recovery file size accepted for reads and writes. */
    const val MAX_FILE_SIZE_BYTES = 1_048_576L

    fun write(
        path: Path,
        params: RecoveryArgon2Params,
        salt: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        overwrite: Boolean = false,
    ) {
        require(salt.size == 16)  { "salt must be 16 bytes" }
        require(nonce.size == 12) { "nonce must be 12 bytes" }
        require(ciphertext.size >= MIN_CIPHERTEXT_LEN) { "ciphertext must include at least a 16-byte authentication tag" }
        require(HEADER_LEN + ciphertext.size <= MAX_FILE_SIZE_BYTES) {
            "recovery file would exceed $MAX_FILE_SIZE_BYTES bytes"
        }

        if (path.exists() && !overwrite) {
            throw RecoveryFileException("refusing to overwrite existing file: $path")
        }
        val parent = path.toAbsolutePath().parent
            ?: throw RecoveryFileException("invalid target (no parent): $path")
        Files.createDirectories(parent)

        val tmp = Files.createTempFile(parent, ".recovery-", ".tmp")
        try {
            DataOutputStream(Files.newOutputStream(tmp)).use { out ->
                out.write(MAGIC)
                out.writeByte(FORMAT_VERSION.toInt())
                out.writeByte(MAGIC_TRAILER.toInt())
                out.writeInt(params.memoryKib)
                out.writeInt(params.iterations)
                out.writeInt(params.parallelism)
                out.write(salt)
                out.write(nonce)
                out.write(ciphertext)
            }
            applyOwnerOnlyPermissions(tmp)
            installFile(tmp, path, overwrite) {
                RecoveryFileException("refusing to overwrite existing file: $path")
            }
        } catch (t: Throwable) {
            try { Files.deleteIfExists(tmp) } catch (_: Throwable) {}
            if (t is RecoveryFileException) throw t
            throw RecoveryFileException("failed to write $path: ${t.message}", t)
        }
    }

    fun read(path: Path): RecoveryFileContents {
        if (!path.exists()) {
            throw RecoveryFileException("recovery file not found: $path")
        }
        val fileSize = try { Files.size(path) } catch (t: Throwable) {
            throw RecoveryFileException("failed to stat $path: ${t.message}", t)
        }
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            throw RecoveryFileException("recovery file exceeds maximum size of $MAX_FILE_SIZE_BYTES bytes: $fileSize bytes")
        }
        val bytes = try { Files.readAllBytes(path) } catch (t: Throwable) {
            throw RecoveryFileException("failed to read $path: ${t.message}", t)
        }
        if (bytes.size < HEADER_LEN) {
            throw RecoveryFileException("file too short to be a recovery file: ${bytes.size} bytes")
        }
        if (!bytes.copyOfRange(0, 6).contentEquals(MAGIC)) {
            throw RecoveryFileException("bad magic - not a recovery file")
        }
        val version = bytes[6]
        if (version != FORMAT_VERSION) {
            throw RecoveryFileException("unsupported recovery file format version: $version")
        }
        if (bytes[7] != MAGIC_TRAILER) {
            throw RecoveryFileException("malformed recovery file header")
        }

        DataInputStream(java.io.ByteArrayInputStream(bytes, 8, bytes.size - 8)).use { dis ->
            val memoryKib   = dis.readInt()
            val iterations  = dis.readInt()
            val parallelism = dis.readInt()
            val salt        = ByteArray(16).also { dis.readFully(it) }
            val nonce       = ByteArray(12).also { dis.readFully(it) }
            val ciphertext  = ByteArray(bytes.size - HEADER_LEN).also { dis.readFully(it) }
            val params = try {
                RecoveryArgon2Params(memoryKib, iterations, parallelism)
            } catch (e: IllegalArgumentException) {
                throw RecoveryFileException("invalid Argon2 params in file: ${e.message}", e)
            }
            return RecoveryFileContents(params, salt, nonce, ciphertext)
        }
    }

    private fun applyOwnerOnlyPermissions(path: Path) {
        if (!Files.getFileStore(path).supportsFileAttributeView("posix")) return
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {}
    }
}
