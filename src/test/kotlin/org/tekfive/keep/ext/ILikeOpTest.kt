package org.tekfive.keep.ext

import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.tekfive.keep.array.setArray
import org.tekfive.keep.text.ArrayToStringOp
import org.tekfive.keep.text.ILikeEscapeOp
import org.tekfive.keep.text.arrayILike
import org.tekfive.keep.text.ilike
import org.tekfive.keep.text.toILikePattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private object ILikeTestTable : Table("ilike_test") {
    val name = varchar("name", 100)
    val description = text("description").nullable()
    val tags = array<String>("tags", VarCharColumnType(100))
    val tagSet = setArray("tag_set", VarCharColumnType(100))
    val aliases = array<String>("aliases", VarCharColumnType(100)).nullable()
}

class ToILikePatternTest {

    @Test
    fun `wraps plain text with wildcards`() {
        assertEquals("%corey%", toILikePattern("corey"))
    }

    @Test
    fun `replaces asterisk with percent`() {
        assertEquals("corey%", toILikePattern("corey*"))
    }

    @Test
    fun `replaces leading asterisk`() {
        assertEquals("%corey", toILikePattern("*corey"))
    }

    @Test
    fun `replaces multiple asterisks`() {
        assertEquals("%corey%baswell%", toILikePattern("*corey*baswell*"))
    }

    @Test
    fun `does not double-wrap when asterisk present`() {
        assertEquals("%test%", toILikePattern("*test*"))
    }

    @Test
    fun `single asterisk becomes single percent`() {
        assertEquals("%", toILikePattern("*"))
    }

    @Test
    fun `empty string gets wrapped`() {
        assertEquals("%%", toILikePattern(""))
    }
}

class ILikeOpTest {

    @Test
    fun `ilike with string produces ILikeEscapeOp`() {
        val op = ILikeTestTable.name ilike "test"
        assertIs<ILikeEscapeOp>(op)
    }

    @Test
    fun `ilike uses ILIKE operator`() {
        val op = ILikeTestTable.name ilike "test"
        assertEquals("ILIKE", op.opSign)
    }

    @Test
    fun `ilike with string has no escape char`() {
        val op = ILikeTestTable.name ilike "test"
        assertNull(op.escapeChar)
    }

    @Test
    fun `ilike with LikePattern preserves escape char`() {
        val op = ILikeTestTable.name ilike LikePattern("%test\\%more%", '\\')
        assertEquals('\\', op.escapeChar)
    }

    @Test
    fun `ilike with LikePattern without escape char`() {
        val op = ILikeTestTable.name ilike LikePattern("%test%")
        assertNull(op.escapeChar)
    }

    @Test
    fun `ilike works on nullable string columns`() {
        val op = ILikeTestTable.description ilike "search"
        assertIs<ILikeEscapeOp>(op)
        assertEquals("ILIKE", op.opSign)
    }

    @Test
    fun `arrayILike produces ILikeEscapeOp with ArrayToStringOp`() {
        val op = ILikeTestTable.tags arrayILike "test"
        assertIs<ILikeEscapeOp>(op)
        assertEquals("ILIKE", op.opSign)
        assertIs<ArrayToStringOp>(op.expr1)
    }

    @Test
    fun `arrayILike works on nullable array columns`() {
        val op = ILikeTestTable.aliases arrayILike "search"
        assertIs<ILikeEscapeOp>(op)
        assertIs<ArrayToStringOp>(op.expr1)
    }

    @Test
    fun `arrayILike works on set array columns`() {
        val op = ILikeTestTable.tagSet arrayILike "search"
        assertIs<ILikeEscapeOp>(op)
        assertIs<ArrayToStringOp>(op.expr1)
    }

    @Test
    fun `arrayILike has no escape char`() {
        val op = ILikeTestTable.tags arrayILike "test"
        assertNull(op.escapeChar)
    }
}
