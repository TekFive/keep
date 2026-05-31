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
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class EncryptedBinaryColumnTypeTest {

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

    private fun createColumnType(associatedData: String = "test_table.test_column"): EncryptedBinaryColumnType {
        return EncryptedBinaryColumnType(associatedData, testAead)
    }

    @Test
    fun `should round-trip byte array through notNullValueToDB and valueFromDB`() {
        val columnType = createColumnType()
        val original = "Hello, encrypted world!".toByteArray()

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertContentEquals(original, restored)
    }

    @Test
    fun `should round-trip empty byte array`() {
        val columnType = createColumnType()
        val original = ByteArray(0)

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertContentEquals(original, restored)
    }

    @Test
    fun `should round-trip large binary content`() {
        val columnType = createColumnType()
        val original = ByteArray(100_000) { it.toByte() }

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertContentEquals(original, restored)
    }

    @Test
    fun `should detect tampered ciphertext`() {
        val columnType = createColumnType()
        val original = "sensitive data".toByteArray()

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

        val original = "secret bytes".toByteArray()
        val encrypted = columnTypeA.notNullValueToDB(original) as ByteArray

        assertThrows<GeneralSecurityException> {
            columnTypeB.valueFromDB(encrypted)
        }
    }

    @Test
    fun `should produce different ciphertext for same plaintext`() {
        val columnType = createColumnType()
        val original = "same input".toByteArray()

        val ct1 = columnType.notNullValueToDB(original) as ByteArray
        val ct2 = columnType.notNullValueToDB(original) as ByteArray

        assertTrue(!ct1.contentEquals(ct2))
    }
}
