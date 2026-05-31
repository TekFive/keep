package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.tekfive.jfk.JsonObject
import java.security.GeneralSecurityException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncryptedJsonObjectColumnTypeTest {

    companion object {
        private lateinit var testAead: Aead

        @BeforeAll
        @JvmStatic
        fun setup() {
            AeadConfig.register()
            val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            testAead = handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }
    }

    private fun createColumnType(associatedData: String = "test_table.test_column"): EncryptedJsonObjectColumnType {
        return EncryptedJsonObjectColumnType(associatedData, testAead)
    }

    @Test
    fun `should round-trip JsonObject with string and long values`() {
        val columnType = createColumnType()
        val original = JsonObject()
        original["name"] = "Alice"
        original["age"] = 42L

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertEquals("Alice", restored["name"].string)
        assertEquals(42L, restored["age"].long)
    }

    @Test
    fun `should round-trip empty JsonObject`() {
        val columnType = createColumnType()
        val original = JsonObject()

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertEquals(0, restored.size)
    }

    @Test
    fun `should round-trip nested JsonObject`() {
        val columnType = createColumnType()
        val inner = JsonObject()
        inner["key"] = "value"
        val original = JsonObject()
        original["nested"] = inner

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        val restoredInner = restored["nested"] as JsonObject
        assertEquals(inner["key"], restoredInner["key"])
    }

    @Test
    fun `should detect tampered ciphertext`() {
        val columnType = createColumnType()
        val original = JsonObject()
        original["data"] = "sensitive"

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val tampered = encrypted.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()

        assertThrows<GeneralSecurityException> {
            columnType.valueFromDB(tampered)
        }
    }

    @Test
    fun `should fail decryption with wrong associated data`() {
        val columnTypeA = createColumnType("table_a.column_x")
        val columnTypeB = createColumnType("table_b.column_y")

        val original = JsonObject()
        original["secret"] = "payload"
        val encrypted = columnTypeA.notNullValueToDB(original) as ByteArray

        assertThrows<GeneralSecurityException> {
            columnTypeB.valueFromDB(encrypted)
        }
    }

    @Test
    fun `should produce different ciphertext for same plaintext`() {
        val columnType = createColumnType()
        val original = JsonObject()
        original["key"] = "same input"

        val ct1 = columnType.notNullValueToDB(original) as ByteArray
        val ct2 = columnType.notNullValueToDB(original) as ByteArray

        assertTrue(!ct1.contentEquals(ct2))
    }
}
