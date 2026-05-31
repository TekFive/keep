package org.tekfive.keep.encryption

/** A freshly generated DEK paired with its wrapped-bytes form for persistence. */
data class GeneratedDek(val dek: MessageDek, val wrapped: ByteArray)
