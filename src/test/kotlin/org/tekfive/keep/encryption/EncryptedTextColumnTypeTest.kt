package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncryptedTextColumnTypeTest {

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

    private fun createColumnType(associatedData: String = "test_table.test_column"): EncryptedTextColumnType {
        return EncryptedTextColumnType(associatedData, testAead)
    }

    @Test
    fun `should round-trip string through notNullValueToDB and valueFromDB`() {
        val columnType = createColumnType()
        val original = "Hello, encrypted world!"

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertEquals(original, restored)
    }

    @Test
    fun `should round-trip empty string`() {
        val columnType = createColumnType()
        val original = ""

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertEquals(original, restored)
    }

    @Test
    fun `should round-trip unicode content`() {
        val columnType = createColumnType()
        val original = "日本語テスト • Ünïcödé • 🏥 Healthcare"

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertEquals(original, restored)
    }

    @Test
    fun `should detect tampered ciphertext`() {
        val columnType = createColumnType()
        val original = "sensitive data"

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

        val original = "secret text"
        val encrypted = columnTypeA.notNullValueToDB(original) as ByteArray

        assertThrows<GeneralSecurityException> {
            columnTypeB.valueFromDB(encrypted)
        }
    }

    @Test
    fun `should produce different ciphertext for same plaintext`() {
        val columnType = createColumnType()
        val original = "same input"

        val ct1 = columnType.notNullValueToDB(original) as ByteArray
        val ct2 = columnType.notNullValueToDB(original) as ByteArray

        assertTrue(!ct1.contentEquals(ct2))
    }
}
