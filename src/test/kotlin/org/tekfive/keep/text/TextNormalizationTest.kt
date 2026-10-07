package org.tekfive.keep.text

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.encryption.EncryptedTextColumnType
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TextNormalizationTest {
    @Test
    fun `defaults are a no-op and trim preserves internal whitespace`() {
        val table = object : Table("normalization_defaults") {
            val raw = text("raw")
            val trimmed = text("trimmed").normalizeText(trim = true)
        }
        assertSame(table.raw, table.raw.normalizeText())
        assertEquals("Mary  Jane", table.trimmed.columnType.valueToDB("\t\u2003Mary  Jane\u00a0\n"))
        assertEquals("", table.trimmed.columnType.valueToDB(" \t\n"))
        assertEquals("  Legacy  ", table.trimmed.columnType.valueFromDB("  Legacy  "))
        assertFalse(table.trimmed.columnType.nullable)
    }

    @Test
    fun `case conversion uses invariant locale and supports Unicode expansions`() {
        val table = object : Table("normalization_case") {
            val lower = text("lower").normalizeText(letterCase = TextCase.LOWER)
            val upper = text("upper").normalizeText(letterCase = TextCase.UPPER)
        }
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(" i ", table.lower.columnType.valueToDB(" I "))
            assertEquals("I", table.upper.columnType.valueToDB("i"))
            assertEquals("STRASSE", table.upper.columnType.valueToDB("straße"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `nullability can be applied before or after normalization`() {
        val table = object : Table("normalization_null") {
            val before = text("before").nullable().normalizeText(trim = true)
            val after = text("after").normalizeText(trim = true).nullable()
        }
        for (column in listOf(table.before, table.after)) {
            assertTrue(column.columnType.nullable)
            assertNull(column.columnType.valueToDB(null))
            assertEquals("", column.columnType.valueToDB("  "))
        }
    }

    @Test
    fun `length checks use normalized text including Unicode expansion`() {
        val table = object : Table("normalization_length") {
            val short = varchar("short", 2).normalizeText(trim = true, letterCase = TextCase.UPPER)
            val single = varchar("single", 1).normalizeText(letterCase = TextCase.UPPER)
            val ci = citext("ci", 2).normalizeText(trim = true, letterCase = TextCase.LOWER)
            val quoted = text("quoted").normalizeText(trim = true, letterCase = TextCase.LOWER)
        }
        assertEquals("US", table.short.columnType.valueToDB("  us  "))
        assertEquals("SS", table.short.columnType.valueToDB("ß"))
        assertFailsWith<IllegalArgumentException> { table.single.columnType.valueToDB("ß") }
        assertEquals("us", table.ci.columnType.valueToDB(" US "))
        assertFailsWith<IllegalArgumentException> { table.ci.columnType.valueToDB(" TOO LONG ") }
        assertEquals("?::citext", table.ci.columnType.parameterMarker(" US "))
        assertEquals("'o''k'", table.quoted.columnType.valueToString(" O'K "))
    }

    @Test
    fun `normalizes plaintext before encryption and leaves legacy plaintext unchanged on read`() {
        AeadConfig.register()
        val aead = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        val base = EncryptedTextColumnType("normalized.secret", aead)
        val table = object : Table("normalized_encrypted") {
            val secret = registerColumn<String>("secret", base).normalizeText(trim = true, letterCase = TextCase.LOWER)
        }
        val stored = table.secret.columnType.valueToDB(" Secret VALUE ") as ByteArray
        assertEquals("secret value", base.valueFromDB(stored))
        assertEquals("secret value", table.secret.columnType.valueFromDB(stored))
        assertEquals("BYTEA", table.secret.columnType.sqlType())
        assertEquals(" Legacy ", table.secret.columnType.valueFromDB(base.notNullValueToDB(" Legacy ")))
    }
}
