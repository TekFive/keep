package org.tekfive.keep.encryption

/**
 * Argon2id parameters recorded inside a `keyset.recovery` file. Production
 * defaults target ~1 s derivation on a 2024-era x86_64; tests typically use
 * much smaller values via the wrap-recovery CLI flags.
 */
data class RecoveryArgon2Params(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    init {
        require(memoryKib > 0)    { "memoryKib must be > 0, got $memoryKib" }
        require(iterations > 0)   { "iterations must be > 0, got $iterations" }
        require(parallelism > 0)  { "parallelism must be > 0, got $parallelism" }
        require(memoryKib <= MAX_MEMORY_KIB) { "memoryKib must be <= $MAX_MEMORY_KIB, got $memoryKib" }
        require(iterations <= MAX_ITERATIONS) { "iterations must be <= $MAX_ITERATIONS, got $iterations" }
        require(parallelism <= MAX_PARALLELISM) { "parallelism must be <= $MAX_PARALLELISM, got $parallelism" }
    }

    companion object {
        /** Production defaults: 256 MiB / 4 iter / 4 lanes. */
        val DEFAULTS = RecoveryArgon2Params(
            memoryKib = 262_144,
            iterations = 4,
            parallelism = 4,
        )

        /** Serialized byte length of the three 4-byte big-endian Argon2 parameter integers. */
        const val BYTE_LENGTH = 12

        /** Maximum Argon2 memory cost accepted from recovery files. */
        const val MAX_MEMORY_KIB = 1_048_576

        /** Maximum Argon2 iteration count accepted from recovery files. */
        const val MAX_ITERATIONS = 16

        /** Maximum Argon2 parallelism accepted from recovery files. */
        const val MAX_PARALLELISM = 16
    }
}
