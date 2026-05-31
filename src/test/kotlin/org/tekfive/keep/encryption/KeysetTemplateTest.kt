package org.tekfive.keep.encryption

import com.google.crypto.tink.KeysetHandle
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KeysetTemplateTest {

    @Test
    fun `register() is idempotent`() {
        KeysetTemplate.register()
        KeysetTemplate.register()  // second call must not throw
    }

    @Test
    fun `generateNewKeysetHandle returns a handle with one key`() {
        KeysetTemplate.register()
        val handle: KeysetHandle = KeysetTemplate.generateNewKeysetHandle()
        assertNotNull(handle)
        assertEquals(1, handle.size())
    }

    @Test
    fun `templateName matches the constant used by the app`() {
        // The constant the existing TestDatabase + DatabaseEncryptionProvider
        // align on is AES256_GCM. If this constant ever needs to change, both
        // sides must be updated together.
        assertEquals("AES256_GCM", KeysetTemplate.TEMPLATE_NAME)
    }
}
