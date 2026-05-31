package org.tekfive.keep.ext

import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.array.ArrayOverlapOp
import org.tekfive.keep.data.enumIntersects
import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.dataEnumList
import kotlin.test.Test
import kotlin.test.assertIs

private enum class Priority(override val id: Int, override val displayName: String) : DataEnum {
    LOW(1, "Low"),
    MEDIUM(2, "Medium"),
    HIGH(3, "High"),
}

private object OverlapTestTable : Table("overlap_test") {
    val priorities = dataEnumList<Priority>("priority_ids")
    val optionalPriorities = dataEnumList<Priority>("optional_priority_ids").nullable()
}

class ArrayOverlapOpTest {

    @Test
    fun `enumIntersects produces ArrayOverlapOp`() {
        val op = OverlapTestTable.priorities enumIntersects listOf(Priority.LOW, Priority.HIGH)
        assertIs<ArrayOverlapOp>(op)
    }

    @Test
    fun `enumIntersects works on nullable columns`() {
        val op = OverlapTestTable.optionalPriorities enumIntersects listOf(Priority.MEDIUM)
        assertIs<ArrayOverlapOp>(op)
    }
}
