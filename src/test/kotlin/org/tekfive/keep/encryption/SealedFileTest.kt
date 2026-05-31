package org.tekfive.keep.encryption

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SealedFileTest {

    @Test
    fun `write then read returns the same JWE bytes`(@TempDir tmp: Path) {
        val file = tmp.resolve("keyset.sealed")
        val jwe = "MOCK-JWE:aGVsbG8=".toByteArray()
        SealedFile.write(file, jwe)
        val read = SealedFile.read(file)
        assertContentEquals(jwe, read)
    }

    @Test
    fun `write produces an AIDSEAL header followed by the JWE bytes`(@TempDir tmp: Path) {
        val file = tmp.resolve("keyset.sealed")
        val jwe = "BODY".toByteArray()
        SealedFile.write(file, jwe)
        val raw = Files.readAllBytes(file)
        assertEquals("AIDSEAL\n", String(raw.copyOfRange(0, 8)))
        assertEquals("BODY", String(raw.copyOfRange(8, raw.size)))
    }

    @Test
    fun `read rejects a file with wrong magic`(@TempDir tmp: Path) {
        val bad = tmp.resolve("bad.sealed")
        bad.writeBytes("WRONG_MAGIC_HERE_AND_MORE".toByteArray())
        assertThrows<SealedFileException> { SealedFile.read(bad) }
    }

    @Test
    fun `read rejects a file shorter than the header`(@TempDir tmp: Path) {
        val short = tmp.resolve("short.sealed")
        short.writeBytes("AIDS".toByteArray())
        assertThrows<SealedFileException> { SealedFile.read(short) }
    }

    @Test
    fun `read rejects a missing file`(@TempDir tmp: Path) {
        assertThrows<SealedFileException> { SealedFile.read(tmp.resolve("nope.sealed")) }
    }

    @Test
    fun `write produces file mode 0600 on POSIX`(@TempDir tmp: Path) {
        val file = tmp.resolve("keyset.sealed")
        SealedFile.write(file, "x".toByteArray())
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            val expected = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            assertEquals(expected, Files.getPosixFilePermissions(file))
        }
    }

    @Test
    fun `write refuses to overwrite by default`(@TempDir tmp: Path) {
        val file = tmp.resolve("keyset.sealed")
        SealedFile.write(file, "first".toByteArray())
        assertThrows<SealedFileException> { SealedFile.write(file, "second".toByteArray()) }
    }

    @Test
    fun `write overwrite=true replaces the file`(@TempDir tmp: Path) {
        val file = tmp.resolve("keyset.sealed")
        SealedFile.write(file, "first".toByteArray())
        SealedFile.write(file, "second".toByteArray(), overwrite = true)
        assertContentEquals("second".toByteArray(), SealedFile.read(file))
    }
}
