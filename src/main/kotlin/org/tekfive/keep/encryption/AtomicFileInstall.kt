package org.tekfive.keep.encryption

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Publishes a completed sibling temporary file at [target]. Without [overwrite], the hard-link
 * operation provides atomic create-new semantics even when multiple writers race for the target.
 */
internal fun installFile(
    temporaryFile: Path,
    target: Path,
    overwrite: Boolean,
    alreadyExists: () -> Throwable,
) {
    if (!overwrite) {
        try {
            Files.createLink(target, temporaryFile)
            Files.delete(temporaryFile)
        } catch (_: FileAlreadyExistsException) {
            throw alreadyExists()
        } catch (_: UnsupportedOperationException) {
            try {
                Files.move(temporaryFile, target)
            } catch (_: FileAlreadyExistsException) {
                throw alreadyExists()
            }
        }
        return
    }

    try {
        Files.move(
            temporaryFile,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporaryFile, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
