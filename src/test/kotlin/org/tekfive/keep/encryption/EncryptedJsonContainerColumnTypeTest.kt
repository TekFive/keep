package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonContainer
import org.tekfive.jfk.JsonObject
import java.security.GeneralSecurityException
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncryptedJsonContainerColumnTypeTest {

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

    private fun createColumnType(associatedData: String = "test_table.test_column"): EncryptedJsonContainerColumnType {
        return EncryptedJsonContainerColumnType(associatedData, testAead)
    }

    @Test
    fun `should round-trip JsonObject and restore as JsonObject`() {
        val columnType = createColumnType()
        val original: JsonContainer = JsonObject(mapOf("key" to "value", "count" to 42))

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertIs<JsonObject>(restored)
        assertEquals(original, restored)
    }

    @Test
    fun `should round-trip JsonArray and restore as JsonArray with correct size`() {
        val columnType = createColumnType()
        val original: JsonContainer = JsonArray(listOf("a", "b", "c"))

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertIs<JsonArray>(restored)
        assertEquals(3, restored.size)
        assertEquals(original, restored)
    }

    @Test
    fun `should round-trip empty JsonObject`() {
        val columnType = createColumnType()
        val original: JsonContainer = JsonObject()

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertIs<JsonObject>(restored)
        assertEquals(0, restored.size)
    }

    @Test
    fun `should round-trip empty JsonArray`() {
        val columnType = createColumnType()
        val original: JsonContainer = JsonArray(emptyList())

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertIs<JsonArray>(restored)
        assertEquals(0, restored.size)
    }

    @Test
    fun `should detect tampered ciphertext`() {
        val columnType = createColumnType()
        val original: JsonContainer = JsonObject(mapOf("sensitive" to "data"))

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

        val original: JsonContainer = JsonObject(mapOf("secret" to "value"))
        val encrypted = columnTypeA.notNullValueToDB(original) as ByteArray

        assertThrows<GeneralSecurityException> {
            columnTypeB.valueFromDB(encrypted)
        }
    }

    @Test
    fun `should produce different ciphertext for same plaintext`() {
        val columnType = createColumnType()
        val original: JsonContainer = JsonObject(mapOf("key" to "value"))

        val ct1 = columnType.notNullValueToDB(original) as ByteArray
        val ct2 = columnType.notNullValueToDB(original) as ByteArray

        assertTrue(!ct1.contentEquals(ct2))
    }
}
