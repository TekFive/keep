package org.tekfive.keep.ext

import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.array.ArrayContainsOp
import org.tekfive.keep.array.includes
import kotlin.test.Test
import kotlin.test.assertIs

private object ContainsTestTable : Table("contains_test") {
    val longIds = array<Long>("long_ids")
    val nullableLongIds = array<Long>("nullable_long_ids").nullable()
    val intIds = array<Int>("int_ids")
    val nullableIntIds = array<Int>("nullable_int_ids").nullable()
    val tags = array<String>("tags")
    val nullableTags = array<String>("nullable_tags").nullable()
}

class ArrayContainsOpTest {

    @Test
    fun `includes produces ArrayContainsOp for Long column`() {
        val op = ContainsTestTable.longIds includes 42L
        assertIs<ArrayContainsOp<*>>(op)
    }

    @Test
    fun `includes works on nullable Long column`() {
        val op = ContainsTestTable.nullableLongIds includes 42L
        assertIs<ArrayContainsOp<*>>(op)
    }

    @Test
    fun `includes produces ArrayContainsOp for Int column`() {
        val op = ContainsTestTable.intIds includes 7
        assertIs<ArrayContainsOp<*>>(op)
    }

    @Test
    fun `includes works on nullable Int column`() {
        val op = ContainsTestTable.nullableIntIds includes 7
        assertIs<ArrayContainsOp<*>>(op)
    }

    @Test
    fun `includes produces ArrayContainsOp for String column`() {
        val op = ContainsTestTable.tags includes "active"
        assertIs<ArrayContainsOp<*>>(op)
    }

    @Test
    fun `includes works on nullable String column`() {
        val op = ContainsTestTable.nullableTags includes "active"
        assertIs<ArrayContainsOp<*>>(op)
    }
}
