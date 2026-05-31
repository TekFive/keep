package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.toJsonObject
import java.security.GeneralSecurityException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Simple pass-through type for testing: wraps a JsonObject as ToJson/FromJson. */
data class TestPayload(val entries: Map<String, Any?>) : ToJsonObject {
    override fun toJsonObject(): JsonObject = entries.toJsonObject()

    companion object : FromJsonObject<TestPayload> {
        override fun fromJson(json: JsonObject): TestPayload = TestPayload(json.toMap())
    }
}

class EncryptedToJsonObjectColumnTypeTest {

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

    private fun createColumnType(associatedData: String = "test_table.test_column"): EncryptedToJsonObjectColumnType<TestPayload> {
        return EncryptedToJsonObjectColumnType(TestPayload, associatedData, testAead)
    }

    @Test
    fun `should round-trip a JSON object through notNullValueToDB and valueFromDB`() {
        val columnType = createColumnType()
        val original = TestPayload(mapOf("name" to "test", "count" to 42))

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertEquals("test", restored.entries["name"])
        assertEquals(42, restored.entries["count"])
    }

    @Test
    fun `should round-trip an empty JSON object`() {
        val columnType = createColumnType()
        val original = TestPayload(emptyMap())

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertTrue(restored.entries.isEmpty())
    }

    @Test
    fun `should detect tampered ciphertext`() {
        val columnType = createColumnType()
        val original = TestPayload(mapOf("secret" to "value"))

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

        val original = TestPayload(mapOf("secret" to "value"))
        val encrypted = columnTypeA.notNullValueToDB(original) as ByteArray

        assertThrows<GeneralSecurityException> {
            columnTypeB.valueFromDB(encrypted)
        }
    }

    @Test
    fun `should produce different ciphertext for same plaintext`() {
        val columnType = createColumnType()
        val original = TestPayload(mapOf("key" to "value"))

        val ct1 = columnType.notNullValueToDB(original) as ByteArray
        val ct2 = columnType.notNullValueToDB(original) as ByteArray

        // AES-GCM uses random nonces, so ciphertexts should differ
        assertTrue(!ct1.contentEquals(ct2))
    }
}
