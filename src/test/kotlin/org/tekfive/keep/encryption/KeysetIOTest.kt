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
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KeysetIOTest {

    @Test
    fun `write produces valid JSON proto that read can parse`(@TempDir tmp: Path) {
        KeysetTemplate.register()
        val handle = KeysetTemplate.generateNewKeysetHandle()
        val out = tmp.resolve("keyset.json")

        KeysetIO.write(handle, out)
        val roundTripped = KeysetIO.read(out)

        assertEquals(handle.size(), roundTripped.size())
        assertEquals(handle.primary.id, roundTripped.primary.id)
    }

    @Test
    fun `write refuses to overwrite an existing file by default`(@TempDir tmp: Path) {
        val target = tmp.resolve("keyset.json")
        target.writeText("existing")
        val handle = KeysetTemplate.generateNewKeysetHandle()

        val ex = assertThrows<KeysetAlreadyExistsException> { KeysetIO.write(handle, target) }
        assertTrue(ex.message!!.contains("refusing to overwrite"))
        assertEquals("existing", target.readText())
    }

    @Test
    fun `write with overwrite = true replaces the file atomically`(@TempDir tmp: Path) {
        val target = tmp.resolve("keyset.json")
        target.writeText("existing")
        val handle = KeysetTemplate.generateNewKeysetHandle()

        KeysetIO.write(handle, target, overwrite = true)
        assertNotNull(KeysetIO.read(target))
        // Output must not be the literal sentinel.
        assertTrue(target.readText().trim() != "existing")
    }

    @Test
    fun `read throws KeysetIOException on missing file`(@TempDir tmp: Path) {
        val ex = assertThrows<KeysetIOException> { KeysetIO.read(tmp.resolve("nope.json")) }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test
    fun `read throws KeysetIOException on malformed input`(@TempDir tmp: Path) {
        val target = tmp.resolve("garbage.json")
        target.writeText("{ not even json")
        val ex = assertThrows<KeysetIOException> { KeysetIO.read(target) }
        assertTrue(ex.message!!.contains("parse"), "expected parse failure: ${ex.message}")
    }

    @Test
    fun `file mode on POSIX systems is 0600 after write`(@TempDir tmp: Path) {
        val target = tmp.resolve("keyset.json")
        val handle = KeysetTemplate.generateNewKeysetHandle()
        KeysetIO.write(handle, target)
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            val perms = Files.getPosixFilePermissions(target)
            // Must be readable + writable by owner only.
            val expected = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            assertEquals(expected, perms)
        }
    }

    @Test
    fun `concurrent no-overwrite writes publish exactly one keyset`(@TempDir tmp: Path) {
        val target = tmp.resolve("keyset.json")
        val writers = 8
        val ready = CountDownLatch(writers)
        val start = CountDownLatch(1)
        val successes = AtomicInteger()
        val executor = Executors.newFixedThreadPool(writers)

        try {
            val futures = (0 until writers).map {
                executor.submit {
                    val handle = KeysetTemplate.generateNewKeysetHandle()
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    try {
                        KeysetIO.write(handle, target)
                        successes.incrementAndGet()
                    } catch (_: KeysetAlreadyExistsException) {
                        // Exactly one writer owns the final path.
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
        assertNotNull(KeysetIO.read(target))
    }
}
