package org.tekfive.keep.ext

import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.data.maxLength
import kotlin.test.Test
import kotlin.test.assertEquals

private object TestColumns : Table("col_ext_test") {
    val varchar = varchar("vc", 100)
    val text = text("txt")
    val char = char("ch")
    val binary = binary("bin", 64)
    val integer = integer("num")
    val bool = bool("flag")
}

class ColumnExtTest {

    @Test
    fun `varchar returns declared length`() {
        assertEquals(100, TestColumns.varchar.maxLength)
    }

    @Test
    fun `char returns 1`() {
        assertEquals(1, TestColumns.char.maxLength)
    }

    @Test
    fun `binary returns declared length`() {
        assertEquals(64, TestColumns.binary.maxLength)
    }

    @Test
    fun `text returns max int`() {
        assertEquals(Int.MAX_VALUE, TestColumns.text.maxLength)
    }

    @Test
    fun `integer returns max int`() {
        assertEquals(Int.MAX_VALUE, TestColumns.integer.maxLength)
    }

    @Test
    fun `bool returns max int`() {
        assertEquals(Int.MAX_VALUE, TestColumns.bool.maxLength)
    }
}
