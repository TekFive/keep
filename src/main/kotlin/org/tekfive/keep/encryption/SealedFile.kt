package org.tekfive.keep.encryption

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists

open class SealedFileException(message: String, cause: Throwable? = null) : Exception(message, cause)
class SealedFileExistsException(path: Path) :
    SealedFileException("refusing to overwrite existing file: $path")

object SealedFile {

    private val MAGIC = "AIDSEAL\n".toByteArray(Charsets.US_ASCII)
    private const val HEADER_LEN = 8

    fun write(path: Path, jwe: ByteArray, overwrite: Boolean = false) {
        if (path.exists() && !overwrite) throw SealedFileExistsException(path)
        val parent = path.toAbsolutePath().parent
            ?: throw SealedFileException("invalid target (no parent): $path")
        Files.createDirectories(parent)
        val tmp = Files.createTempFile(parent, ".sealed-", ".tmp")
        try {
            Files.newOutputStream(tmp).use { out ->
                out.write(MAGIC)
                out.write(jwe)
            }
            if (Files.getFileStore(tmp).supportsFileAttributeView("posix")) {
                try {
                    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
                } catch (_: UnsupportedOperationException) {}
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            try { Files.deleteIfExists(tmp) } catch (_: Throwable) {}
            if (t is SealedFileException) throw t
            throw SealedFileException("failed to write $path: ${t.message}", t)
        }
    }

    fun read(path: Path): ByteArray {
        if (!path.exists()) throw SealedFileException("sealed file not found: $path")
        val bytes = try { Files.readAllBytes(path) } catch (t: Throwable) {
            throw SealedFileException("failed to read $path: ${t.message}", t)
        }
        if (bytes.size < HEADER_LEN) {
            throw SealedFileException("file too short to be a sealed file: ${bytes.size} bytes")
        }
        if (!bytes.copyOfRange(0, HEADER_LEN).contentEquals(MAGIC)) {
            throw SealedFileException("bad magic — not a sealed file")
        }
        return bytes.copyOfRange(HEADER_LEN, bytes.size)
    }
}
