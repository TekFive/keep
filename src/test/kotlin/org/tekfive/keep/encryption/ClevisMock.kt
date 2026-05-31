package org.tekfive.keep.encryption

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Installs a mock `clevis` binary in a temp directory and modifies $PATH for
 * the current process so it's the first match. Use with @TempDir.
 *
 * The mock supports two subcommands:
 *
 *   clevis encrypt tpm2 '<policy>'
 *     - reads plaintext from stdin
 *     - writes "MOCK-JWE:" + base64(plaintext) + "\n" to stdout
 *
 *   clevis decrypt
 *     - reads a MOCK-JWE blob from stdin (matching the above format)
 *     - writes the decoded plaintext to stdout
 *
 * Every invocation is appended to `${dir}/clevis.log`.
 *
 * If MOCK_CLEVIS_FAIL=encrypt or =decrypt is set in the environment, that
 * subcommand exits non-zero with a stderr message — useful for failure-path
 * tests.
 */
object ClevisMock {

    private const val SCRIPT = """#!/usr/bin/env bash
set -euo pipefail

LOG="${'$'}(dirname "${'$'}0")/clevis.log"
printf 'clevis %s\n' "${'$'}*" >> "${'$'}LOG"

case "${'$'}1" in
    encrypt)
        if [[ "${'$'}{MOCK_CLEVIS_FAIL:-}" == "encrypt" ]]; then
            echo "MOCK clevis encrypt failed (forced)" >&2
            exit 1
        fi
        printf 'MOCK-JWE:'
        base64 -w0 < /dev/stdin
        echo
        ;;
    decrypt)
        if [[ "${'$'}{MOCK_CLEVIS_FAIL:-}" == "decrypt" ]]; then
            echo "MOCK clevis decrypt failed (forced)" >&2
            exit 1
        fi
        input="${'$'}(cat)"
        if [[ "${'$'}input" != MOCK-JWE:* ]]; then
            echo "MOCK clevis: bad input (no MOCK-JWE prefix)" >&2
            exit 1
        fi
        printf '%s' "${'$'}{input#MOCK-JWE:}" | base64 -d
        ;;
    *)
        echo "MOCK clevis: unknown subcommand ${'$'}1" >&2
        exit 2
        ;;
esac
"""

    /**
     * Install the mock at `dir/clevis`, make executable.
     * Returns the path to the invocation log file.
     */
    fun install(dir: Path): Path {
        Files.createDirectories(dir)
        val script = dir.resolve("clevis")
        Files.writeString(script, SCRIPT)
        if (Files.getFileStore(script).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        return dir.resolve("clevis.log")
    }

    /**
     * Return the value to use as PATH so that the mock clevis is found first.
     */
    fun pathWithMockClevis(dir: Path): String {
        val existing = System.getenv("PATH") ?: ""
        return "${dir.toAbsolutePath()}:$existing"
    }
}
