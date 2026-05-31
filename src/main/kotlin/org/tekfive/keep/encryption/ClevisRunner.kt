package org.tekfive.keep.encryption

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class TpmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Shellout wrapper around clevis. Tests pass `pathOverride` so a mock clevis
 * is found first. Production callers leave both arguments at their defaults.
 *
 * @param pathOverride value to set for $PATH on the spawned process; null = inherit
 * @param extraEnv extra environment entries to set on the spawned process
 * @param policy clevis policy JSON for the tpm2 pin; default is TPM-only (no PCRs)
 */
class ClevisRunner(
    private val pathOverride: String? = null,
    private val extraEnv: Map<String, String> = emptyMap(),
    private val policy: String = """{"hash":"sha256","key":"rsa"}""",
) {

    /** Seal `plaintext` under the TPM. Returns the JWE bytes. */
    fun encrypt(plaintext: ByteArray): ByteArray =
        run(listOf("clevis", "encrypt", "tpm2", policy), plaintext, "encrypt")

    /** Unseal a JWE blob via the TPM. Returns the plaintext bytes. */
    fun decrypt(jwe: ByteArray): ByteArray =
        run(listOf("clevis", "decrypt"), jwe, "decrypt")

    private fun run(command: List<String>, input: ByteArray, op: String): ByteArray {
        // Resolve the full path to the command using PATH
        val cmdPath = resolveCommand(command[0])
        val fullCommand = listOf(cmdPath) + command.drop(1)

        val pb = ProcessBuilder(fullCommand)
        val env = pb.environment()
        if (pathOverride != null) env["PATH"] = pathOverride
        extraEnv.forEach { (k, v) -> env[k] = v }
        pb.redirectErrorStream(false)

        val process = try {
            pb.start()
        } catch (e: java.io.IOException) {
            throw TpmException("clevis not found on PATH (install clevis-pin-tpm2 package): ${e.message}", e)
        }

        process.outputStream.use { it.write(input) }

        val out = ByteArrayOutputStream()
        process.inputStream.use { it.copyTo(out) }
        val err = process.errorStream.use { it.readBytes().toString(Charsets.UTF_8) }

        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw TpmException("clevis $op timed out after 30s")
        }
        if (process.exitValue() != 0) {
            throw TpmException("clevis $op exited ${process.exitValue()}: ${err.trim()}")
        }
        return out.toByteArray()
    }

    private fun resolveCommand(cmd: String): String {
        // If pathOverride is set, search for the command in that PATH first
        val searchPath = pathOverride ?: System.getenv("PATH") ?: ""
        val pathDirs = searchPath.split(":")

        for (dir in pathDirs) {
            val candidate = File(dir, cmd)
            if (candidate.exists() && candidate.isFile && candidate.canExecute()) {
                return candidate.absolutePath
            }
        }

        // If not found in PATH, return the command as-is (will fail with better error message)
        return cmd
    }
}
