package org.tekfive.keep.encryption

/** Test fixtures: sub-second Argon2id derivation. NOT for production use. */
internal object TestArgon2Params {
    val fast = RecoveryArgon2Params(memoryKib = 8, iterations = 1, parallelism = 1)
}
