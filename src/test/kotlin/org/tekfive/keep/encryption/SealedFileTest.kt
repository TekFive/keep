package org.tekfive.keep.encryption

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun `write leaves no temporary file behind`(@TempDir tmp: Path) {
        SealedFile.write(tmp.resolve("keyset.sealed"), "payload".toByteArray())
        val names = Files.list(tmp).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("keyset.sealed"), names)
    }

    @Test
    fun `write refuses to overwrite and leaves both the existing file and the directory clean`(@TempDir tmp: Path) {
        val file = tmp.resolve("keyset.sealed")
        SealedFile.write(file, "first".toByteArray())
        assertThrows<SealedFileException> { SealedFile.write(file, "second".toByteArray()) }
        assertContentEquals("first".toByteArray(), SealedFile.read(file))
        val names = Files.list(tmp).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("keyset.sealed"), names)
    }

    @Test
    fun `concurrent no-overwrite writes publish exactly one payload`(@TempDir tmp: Path) {
        val file = tmp.resolve("evidence.sealed")
        val writers = 16
        val ready = CountDownLatch(writers)
        val start = CountDownLatch(1)
        val successes = AtomicInteger()
        val executor = Executors.newFixedThreadPool(writers)

        try {
            val futures = (0 until writers).map { index ->
                executor.submit {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    try {
                        SealedFile.write(file, "payload-$index".toByteArray())
                        successes.incrementAndGet()
                    } catch (_: SealedFileExistsException) {
                        // One writer owns the final path; every other writer must observe it.
                    }
                }
            }
            ready.await(5, TimeUnit.SECONDS)
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, successes.get())
        val stored = String(SealedFile.read(file))
        assertEquals(true, stored.startsWith("payload-"))
    }

    @Test
    fun `bounded read rejects an oversized sealed file`(@TempDir tmp: Path) {
        val file = tmp.resolve("large.sealed")
        SealedFile.write(file, ByteArray(1024))

        assertThrows<SealedFileException> { SealedFile.read(file, maximumFileBytes = 128) }
    }

    @Test
    fun `write round-trips a payload larger than a single channel write`(@TempDir tmp: Path) {
        val file = tmp.resolve("large.sealed")
        val payload = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        SealedFile.write(file, payload)
        assertContentEquals(payload, SealedFile.read(file))
    }

    @Test
    fun `write creates missing parent directories`(@TempDir tmp: Path) {
        val file = tmp.resolve("nested").resolve("deeper").resolve("keyset.sealed")
        SealedFile.write(file, "payload".toByteArray())
        assertContentEquals("payload".toByteArray(), SealedFile.read(file))
    }
}
