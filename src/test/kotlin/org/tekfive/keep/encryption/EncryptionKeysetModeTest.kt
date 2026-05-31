package org.tekfive.keep.encryption

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EncryptionKeysetModeTest {
    @Test
    fun `parse accepts each value case-insensitively`() {
        assertEquals(EncryptionKeysetMode.PLAINTEXT, EncryptionKeysetMode.parse("plaintext"))
        assertEquals(EncryptionKeysetMode.PLAINTEXT, EncryptionKeysetMode.parse("PLAINTEXT"))
        assertEquals(EncryptionKeysetMode.SEALED, EncryptionKeysetMode.parse("Sealed"))
        assertEquals(EncryptionKeysetMode.RECOVERY, EncryptionKeysetMode.parse("recovery"))
    }

    @Test
    fun `parse rejects unknown values`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            EncryptionKeysetMode.parse("scribbled")
        }
        assertEquals(true, ex.message!!.contains("scribbled"))
    }
}
