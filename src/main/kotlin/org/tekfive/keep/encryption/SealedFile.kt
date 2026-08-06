package org.tekfive.keep.encryption

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists

open class SealedFileException(message: String, cause: Throwable? = null) : Exception(message, cause)
class SealedFileExistsException(path: Path) :
    SealedFileException("refusing to overwrite existing file: $path")

object SealedFile {

    private val MAGIC = "AIDSEAL\n".toByteArray(Charsets.US_ASCII)
    private const val HEADER_LEN = 8

    private val log = LoggerFactory.getLogger(SealedFile::class.java)

    /**
     * Writes [jwe] behind the sealed-file header.
     *
     * The write is durable, not merely atomic: the payload is fsynced to the storage device before
     * the temporary file is renamed into place, the rename is atomic (the temporary file is created
     * in the target's own directory, so the same filesystem is guaranteed), and the parent directory
     * is fsynced afterwards so the new directory entry survives a power loss as well. Callers that
     * treat a successful return as "this is on disk" — the audit spool, the keyset writer — need all
     * three: without the payload fsync a crash can leave a correctly named but empty file, and
     * without the directory fsync the file's contents can survive while its name does not.
     *
     * Existing targets are refused unless [overwrite] is true.
     */
    fun write(path: Path, jwe: ByteArray, overwrite: Boolean = false) {
        if (path.exists() && !overwrite) throw SealedFileExistsException(path)
        val parent = path.toAbsolutePath().parent
            ?: throw SealedFileException("invalid target (no parent): $path")
        Files.createDirectories(parent)
        val tmp = Files.createTempFile(parent, ".sealed-", ".tmp")
        try {
            if (Files.getFileStore(tmp).supportsFileAttributeView("posix")) {
                try {
                    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
                } catch (_: UnsupportedOperationException) {}
            }
            FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                writeFully(channel, MAGIC)
                writeFully(channel, jwe)
                channel.force(true)
            }
            moveIntoPlace(tmp, path, overwrite)
            syncDirectory(parent)
        } catch (t: Throwable) {
            try { Files.deleteIfExists(tmp) } catch (_: Throwable) {}
            if (t is SealedFileException) throw t
            throw SealedFileException("failed to write $path: ${t.message}", t)
        }
    }

    fun read(path: Path, maximumFileBytes: Long = Int.MAX_VALUE.toLong()): ByteArray {
        require(maximumFileBytes >= HEADER_LEN) { "maximumFileBytes must include the sealed-file header." }
        if (!path.exists()) throw SealedFileException("sealed file not found: $path")
        val reportedSize = try { Files.size(path) } catch (t: Throwable) {
            throw SealedFileException("failed to inspect $path: ${t.message}", t)
        }
        if (reportedSize > maximumFileBytes) {
            throw SealedFileException("sealed file exceeds the configured maximum size: $reportedSize bytes")
        }

        val bytes = try {
            val output = ByteArrayOutputStream(minOf(reportedSize, READ_BUFFER_SIZE.toLong()).toInt())
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(READ_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximumFileBytes) {
                        throw SealedFileException("sealed file exceeds the configured maximum size: more than $maximumFileBytes bytes")
                    }
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        } catch (t: Throwable) {
            if (t is SealedFileException) throw t
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

    /** A single [FileChannel.write] may write fewer bytes than the buffer holds. */
    private fun writeFully(channel: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private fun moveIntoPlace(tmp: Path, path: Path, overwrite: Boolean) {
        if (!overwrite) {
            installWithoutReplace(tmp, path)
            return
        }

        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Atomically publishes a completed temporary file without ever replacing an existing target.
     * A pre-flight existence check cannot provide that guarantee because another writer may create
     * the target between the check and installation. Creating a hard link is an atomic CREATE_NEW
     * operation on the same filesystem; the temporary name is removed only after the final name is
     * installed.
     */
    private fun installWithoutReplace(tmp: Path, path: Path) {
        try {
            Files.createLink(path, tmp)
            Files.delete(tmp)
        } catch (_: FileAlreadyExistsException) {
            throw SealedFileExistsException(path)
        } catch (_: UnsupportedOperationException) {
            // The fallback retains no-replace semantics, though a filesystem without hard links
            // cannot also provide the atomic publication guarantee.
            try {
                Files.move(tmp, path)
            } catch (_: FileAlreadyExistsException) {
                throw SealedFileExistsException(path)
            }
        }
    }

    private fun syncDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (t: Throwable) {
            // Opening a directory as a channel is not portable (Windows refuses it outright, and
            // some network filesystems reject the force). The payload fsync and the atomic rename
            // have already happened, so only the durability of the directory entry is lost — not
            // worth failing an otherwise complete write.
            log.debug("Unable to fsync directory {}: {}", directory, t.message)
        }
    }

    private const val READ_BUFFER_SIZE = 8192
}
