package org.tekfive.keep.job.db

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class QueryNodeTest {

    private val col = "job_details"

    @Test
    fun `Condition delegates to operator SQL`() {
        val op = JsonPathOperator.GTE(listOf("age"), 18)
        val node = QueryNode.Condition(op)
        assertEquals("(jsonb_extract_path_text($col, ?))::numeric >= ?", node.toParameterizedSql(col))
    }

    @Test
    fun `Group with AND joins children`() {
        val c1 = QueryNode.Condition(JsonPathOperator.GTE(listOf("a"), 1))
        val c2 = QueryNode.Condition(JsonPathOperator.LTE(listOf("b"), 10))
        val group = QueryNode.Group(LogicalOperator.AND, listOf(c1, c2))
        assertEquals(
            "((jsonb_extract_path_text($col, ?))::numeric >= ? AND (jsonb_extract_path_text($col, ?))::numeric <= ?)",
            group.toParameterizedSql(col)
        )
    }

    @Test
    fun `Group with OR joins children`() {
        val c1 = QueryNode.Condition(JsonPathOperator.Equals(listOf("status"), "active"))
        val c2 = QueryNode.Condition(JsonPathOperator.Equals(listOf("status"), "pending"))
        val group = QueryNode.Group(LogicalOperator.OR, listOf(c1, c2))
        assertEquals(
            "(jsonb_extract_path_text($col, ?) = ? OR jsonb_extract_path_text($col, ?) = ?)",
            group.toParameterizedSql(col)
        )
    }

    @Test
    fun `nested groups produce correct parenthesized SQL`() {
        val c1 = QueryNode.Condition(JsonPathOperator.GTE(listOf("x"), 1))
        val c2 = QueryNode.Condition(JsonPathOperator.LTE(listOf("x"), 10))
        val inner = QueryNode.Group(LogicalOperator.AND, listOf(c1, c2))

        val c3 = QueryNode.Condition(JsonPathOperator.Equals(listOf("type"), "special"))
        val outer = QueryNode.Group(LogicalOperator.OR, listOf(inner, c3))

        assertEquals(
            "(((jsonb_extract_path_text($col, ?))::numeric >= ? AND (jsonb_extract_path_text($col, ?))::numeric <= ?) OR jsonb_extract_path_text($col, ?) = ?)",
            outer.toParameterizedSql(col)
        )
    }
}
