package org.tekfive.keep.encryption

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.tekfive.keep.data.DataEnum
import java.security.GeneralSecurityException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncryptedDataEnumColumnTypeTest {

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

    private fun createColumnType(
        associatedData: String = "test_table.test_column",
    ): EncryptedDataEnumColumnType<SensitiveStatus> =
        EncryptedDataEnumColumnType(enumValues<SensitiveStatus>(), associatedData, testAead)

    private fun createListColumnType(
        associatedData: String = "test_table.test_column",
    ): EncryptedDataEnumListColumnType<SensitiveStatus> =
        EncryptedDataEnumListColumnType(enumValues<SensitiveStatus>(), associatedData, testAead)

    @Test
    fun `should round-trip data enum through notNullValueToDB and valueFromDB`() {
        val columnType = createColumnType()

        val encrypted = columnType.notNullValueToDB(SensitiveStatus.RESTRICTED) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertEquals(SensitiveStatus.RESTRICTED, restored)
    }

    @Test
    fun `should round-trip encrypted data enum list`() {
        val columnType = createListColumnType()
        val original = listOf(SensitiveStatus.PUBLIC, SensitiveStatus.RESTRICTED)

        val encrypted = columnType.notNullValueToDB(original) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertEquals(original, restored)
    }

    @Test
    fun `should round-trip empty encrypted data enum list`() {
        val columnType = createListColumnType()

        val encrypted = columnType.notNullValueToDB(emptyList()) as ByteArray
        val restored = columnType.valueFromDB(encrypted)

        assertTrue(restored.isEmpty())
    }

    @Test
    fun `should fail decryption with wrong associated data`() {
        val columnTypeA = createColumnType("table_a.column_x")
        val columnTypeB = createColumnType("table_b.column_y")

        val encrypted = columnTypeA.notNullValueToDB(SensitiveStatus.INTERNAL) as ByteArray

        assertThrows<GeneralSecurityException> {
            columnTypeB.valueFromDB(encrypted)
        }
    }

    @Test
    fun `should produce different ciphertext for same enum value`() {
        val columnType = createColumnType()

        val ct1 = columnType.notNullValueToDB(SensitiveStatus.INTERNAL) as ByteArray
        val ct2 = columnType.notNullValueToDB(SensitiveStatus.INTERNAL) as ByteArray

        assertTrue(!ct1.contentEquals(ct2))
    }

    @Test
    fun `should reject duplicate data enum ids`() {
        assertThrows<IllegalStateException> {
            EncryptedDataEnumColumnType(enumValues<DuplicateSensitiveStatus>(), "test_table.test_column", testAead)
        }
    }
}

private enum class SensitiveStatus(override val id: Int, override val displayName: String) : DataEnum {
    PUBLIC(1, "Public"),
    INTERNAL(2, "Internal"),
    RESTRICTED(3, "Restricted"),
}

private enum class DuplicateSensitiveStatus(override val id: Int, override val displayName: String) : DataEnum {
    A(1, "A"),
    B(1, "B"),
}
