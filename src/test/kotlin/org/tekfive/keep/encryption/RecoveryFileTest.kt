package org.tekfive.keep.encryption

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RecoveryFileTest {

    private val testParams = RecoveryArgon2Params(memoryKib = 8, iterations = 1, parallelism = 1)
    private val salt = ByteArray(16) { it.toByte() }
    private val nonce = ByteArray(12) { (it + 100).toByte() }
    private val ciphertext = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10) + ByteArray(16)

    @Test
    fun `write then read returns the same params, salt, nonce, ciphertext`(@TempDir tmp: Path) {
        val file = tmp.resolve("k.recovery")
        RecoveryFile.write(file, testParams, salt, nonce, ciphertext)

        val parsed = RecoveryFile.read(file)
        assertEquals(testParams, parsed.params)
        assertContentEquals(salt, parsed.salt)
        assertContentEquals(nonce, parsed.nonce)
        assertContentEquals(ciphertext, parsed.ciphertext)
    }

    @Test
    fun `read rejects a file with wrong magic`(@TempDir tmp: Path) {
        val bogus = tmp.resolve("bogus.recovery")
        bogus.writeBytes(byteArrayOf('X'.code.toByte(), 'X'.code.toByte()) + ByteArray(100))
        assertThrows<RecoveryFileException> { RecoveryFile.read(bogus) }
    }

    @Test
    fun `read rejects a file shorter than the header`(@TempDir tmp: Path) {
        val truncated = tmp.resolve("short.recovery")
        truncated.writeBytes("AIDREC\n".toByteArray() + ByteArray(20))
        assertThrows<RecoveryFileException> { RecoveryFile.read(truncated) }
    }

    @Test
    fun `read rejects unsupported format version`(@TempDir tmp: Path) {
        val bad = tmp.resolve("v2.recovery")
        val header = byteArrayOf(
            'A'.code.toByte(), 'I'.code.toByte(), 'D'.code.toByte(),
            'R'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte(),
            0x02,                                  // version 2 (unsupported)
            '\n'.code.toByte(),
        ) + ByteArray(40) + ByteArray(20)
        bad.writeBytes(header)
        assertThrows<RecoveryFileException> { RecoveryFile.read(bad) }
    }

    @Test
    fun `read rejects files larger than maximum size`(@TempDir tmp: Path) {
        val oversized = tmp.resolve("huge.recovery")
        Files.newOutputStream(oversized).use { out ->
            out.write(ByteArray(RecoveryFile.MAX_FILE_SIZE_BYTES.toInt() + 1))
        }

        assertThrows<RecoveryFileException> { RecoveryFile.read(oversized) }
    }

    @Test
    fun `read rejects excessive Argon2 params from file`(@TempDir tmp: Path) {
        val bad = tmp.resolve("bad-params.recovery")
        DataOutputStream(Files.newOutputStream(bad)).use { out ->
            out.write(byteArrayOf(
                'A'.code.toByte(), 'I'.code.toByte(), 'D'.code.toByte(),
                'R'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte(),
            ))
            out.writeByte(0x01)
            out.writeByte('\n'.code)
            out.writeInt(RecoveryArgon2Params.MAX_MEMORY_KIB + 1)
            out.writeInt(1)
            out.writeInt(1)
            out.write(ByteArray(16))
            out.write(ByteArray(12))
            out.write(ByteArray(16))
        }

        assertThrows<RecoveryFileException> { RecoveryFile.read(bad) }
    }

    @Test
    fun `write rejects oversized recovery file`() {
        assertThrows<IllegalArgumentException> {
            RecoveryFile.write(
                Path.of("oversized.recovery"),
                testParams,
                salt,
                nonce,
                ByteArray(RecoveryFile.MAX_FILE_SIZE_BYTES.toInt())
            )
        }
    }

    @Test
    fun `write produces file mode 0600 on POSIX`(@TempDir tmp: Path) {
        val file = tmp.resolve("k.recovery")
        RecoveryFile.write(file, testParams, salt, nonce, ciphertext)
        if (java.nio.file.Files.getFileStore(file).supportsFileAttributeView("posix")) {
            val expected = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            assertEquals(expected, java.nio.file.Files.getPosixFilePermissions(file))
        }
    }

    @Test
    fun `write refuses to overwrite by default`(@TempDir tmp: Path) {
        val file = tmp.resolve("k.recovery")
        RecoveryFile.write(file, testParams, salt, nonce, ciphertext)
        assertThrows<RecoveryFileException> {
            RecoveryFile.write(file, testParams, salt, nonce, ciphertext)
        }
    }
}
