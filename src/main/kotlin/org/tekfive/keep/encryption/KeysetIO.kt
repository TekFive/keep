package org.tekfive.keep.encryption

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.readText

open class KeysetIOException(message: String, cause: Throwable? = null) : Exception(message, cause)

class KeysetAlreadyExistsException(path: java.nio.file.Path) :
    KeysetIOException("refusing to overwrite existing file: $path")

object KeysetIO {

    /**
     * Read a Tink keyset from `path` in JSON proto format (the format the
     * existing app's DatabaseEncryptionProvider also uses).
     *
     * @throws KeysetIOException on missing file or malformed content
     */
    fun read(path: Path): KeysetHandle {
        if (!path.exists()) {
            throw KeysetIOException("keyset file not found: $path")
        }
        KeysetTemplate.register()
        val text = try {
            path.readText()
        } catch (t: Throwable) {
            throw KeysetIOException("failed to read keyset file: $path", t)
        }
        return try {
            TinkJsonProtoKeysetFormat.parseKeyset(text, InsecureSecretKeyAccess.get())
        } catch (t: Throwable) {
            throw KeysetIOException("failed to parse keyset at $path: ${t.message}", t)
        }
    }

    /**
     * Write a Tink keyset to `path` in JSON proto format with mode 0600.
     *
     * Writes atomically: serializes to a sibling temp file, sets mode, then
     * renames. If `overwrite` is false and `path` exists, throws without
     * touching the target.
     *
     * @throws KeysetIOException on any failure
     */
    fun write(handle: KeysetHandle, path: Path, overwrite: Boolean = false) {
        if (path.exists() && !overwrite) {
            throw KeysetAlreadyExistsException(path)
        }
        KeysetTemplate.register()
        val parent = path.toAbsolutePath().parent
            ?: throw KeysetIOException("invalid target path (no parent): $path")
        Files.createDirectories(parent)

        val json = try {
            TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())
        } catch (t: Throwable) {
            throw KeysetIOException("failed to serialize keyset: ${t.message}", t)
        }

        val temp = Files.createTempFile(parent, ".keyset-", ".tmp")
        try {
            Files.writeString(temp, json)
            // Set mode 0600 on POSIX systems before the rename so the file is
            // never world-readable, even briefly.
            applyOwnerOnlyPermissions(temp)
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            // Ensure no leftover temp file on failure.
            try { Files.deleteIfExists(temp) } catch (_: Throwable) { /* best effort */ }
            if (t is KeysetIOException) throw t
            throw KeysetIOException("failed to write keyset to $path: ${t.message}", t)
        }
    }

    private fun applyOwnerOnlyPermissions(path: Path) {
        if (!Files.getFileStore(path).supportsFileAttributeView("posix")) return
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem; nothing to enforce.
        }
    }
}
