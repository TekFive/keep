package org.tekfive.keep.job.db

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class JsonPathOperatorTest {

    private val col = "details"

    @Test
    fun `GTE single key path`() {
        val op = JsonPathOperator.GTE(listOf("age"), 18)
        assertEquals("(jsonb_extract_path_text($col, ?))::numeric >= ?", op.toParameterizedSql(col))
    }

    @Test
    fun `GT single key path`() {
        val op = JsonPathOperator.GT(listOf("score"), 100)
        assertEquals("(jsonb_extract_path_text($col, ?))::numeric > ?", op.toParameterizedSql(col))
    }

    @Test
    fun `LTE single key path`() {
        val op = JsonPathOperator.LTE(listOf("count"), 5)
        assertEquals("(jsonb_extract_path_text($col, ?))::numeric <= ?", op.toParameterizedSql(col))
    }

    @Test
    fun `LT single key path`() {
        val op = JsonPathOperator.LT(listOf("priority"), 3)
        assertEquals("(jsonb_extract_path_text($col, ?))::numeric < ?", op.toParameterizedSql(col))
    }

    @Test
    fun `Equals single key path`() {
        val op = JsonPathOperator.Equals(listOf("status"), "active")
        assertEquals("jsonb_extract_path_text($col, ?) = ?", op.toParameterizedSql(col))
    }

    @Test
    fun `Contains single key path`() {
        val op = JsonPathOperator.Contains(listOf("tags"), "urgent")
        assertEquals("(jsonb_extract_path_text($col, ?))::jsonb ?? ?", op.toParameterizedSql(col))
    }

    @Test
    fun `GTE nested path`() {
        val op = JsonPathOperator.GTE(listOf("config", "timeout"), 30)
        assertEquals("(jsonb_extract_path_text($col, ?, ?))::numeric >= ?", op.toParameterizedSql(col))
    }

    @Test
    fun `Equals deeply nested path`() {
        val op = JsonPathOperator.Equals(listOf("a", "b", "c"), "val")
        assertEquals("jsonb_extract_path_text($col, ?, ?, ?) = ?", op.toParameterizedSql(col))
    }

    @Test
    fun `path keys are parameterized`() {
        val op = JsonPathOperator.Equals(listOf("tenant's", "status"), "active")
        assertEquals("jsonb_extract_path_text($col, ?, ?) = ?", op.toParameterizedSql(col))
    }

    @Test
    fun `empty path is rejected`() {
        assertThrows<IllegalArgumentException> {
            JsonPathOperator.Equals(emptyList(), "active")
        }
    }

    @Test
    fun `empty path key is rejected`() {
        assertThrows<IllegalArgumentException> {
            JsonPathOperator.Equals(listOf(""), "active")
        }
    }

    @Test
    fun `assertValidNumber rejects BigDecimal`() {
        assertThrows<IllegalArgumentException> {
            JsonPathOperator.GTE(listOf("x"), java.math.BigDecimal("1.0"))
        }
    }

    @Test
    fun `assertValidNumber rejects BigInteger`() {
        assertThrows<IllegalArgumentException> {
            JsonPathOperator.GT(listOf("x"), java.math.BigInteger("1"))
        }
    }

    @Test
    fun `assertValidNumber accepts Int`() {
        JsonPathOperator.GTE(listOf("x"), 42)
    }

    @Test
    fun `assertValidNumber accepts Long`() {
        JsonPathOperator.LTE(listOf("x"), 42L)
    }

    @Test
    fun `assertValidNumber accepts Double`() {
        JsonPathOperator.LT(listOf("x"), 3.14)
    }

    @Test
    fun `Equals rejects non-string non-number`() {
        assertThrows<IllegalArgumentException> {
            JsonPathOperator.Equals(listOf("x"), listOf("not valid"))
        }
    }
}
