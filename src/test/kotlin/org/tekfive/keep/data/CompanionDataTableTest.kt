package org.tekfive.keep.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompanionDataTableTest {
    class LongRecord(var label: String) : Data() {
        companion object : DataTable<LongRecord>("companion_long_records") {
            val labelColumn = column(LongRecord::label)
        }
    }

    class UuidRecord(var label: String) : UuidData() {
        companion object Records : UuidDataTable<UuidRecord>("companion_uuid_records") {
            val labelColumn = column(UuidRecord::label)
        }
    }

    class LongWithOrdinaryCompanion(val label: String) : Data() {
        companion object
    }

    class UuidWithOrdinaryCompanion(val label: String) : UuidData() {
        companion object
    }

    class LongWithWrongTable(val label: String) : Data() {
        companion object : DataTable<LongRecord>("wrong_long_companion")
    }

    class UuidWithWrongTable(val label: String) : UuidData() {
        companion object : UuidDataTable<UuidRecord>("wrong_uuid_companion")
    }

    open class ParentRecord(val label: String) : Data() {
        companion object : DataTable<ParentRecord>("parent_companion") {
            val labelColumn = column(ParentRecord::label)
        }
    }

    class ChildRecord(label: String) : ParentRecord(label)

    @Test
    fun `resolves the companion table for the runtime data class without a database`() {
        val long: Data = LongRecord("long")
        val uuid: UuidData = UuidRecord("uuid")
        assertSame(LongRecord, long.table)
        assertSame(UuidRecord.Records, uuid.table)
        assertSame(long.table, LongRecord("another").table)
        assertSame(uuid.table, UuidRecord("another").table)
        assertSame(LongRecord.labelColumn, long.table.columnPropertyMap["label"])
        assertSame(UuidRecord.labelColumn, uuid.table.columnPropertyMap["label"])
        assertFalse(long.linkedToDb)
        assertFalse(uuid.linkedToDb)
    }

    @Test
    fun `throws when the table is a separate object rather than a companion`() {
        val longError = assertFailsWith<IllegalStateException> { SimpleData("long", 1).table }
        val uuidError = assertFailsWith<IllegalStateException> { UuidSimpleData("uuid", 1).table }
        assertTrue(longError.message.orEmpty().contains("DataTable<SimpleData>"))
        assertTrue(uuidError.message.orEmpty().contains("UuidDataTable<UuidSimpleData>"))
    }

    @Test
    fun `throws when the companion is not a table`() {
        assertFailsWith<IllegalStateException> { LongWithOrdinaryCompanion("long").table }
        assertFailsWith<IllegalStateException> { UuidWithOrdinaryCompanion("uuid").table }
    }

    @Test
    fun `throws when the companion table maps a different data class`() {
        assertFailsWith<IllegalStateException> { LongWithWrongTable("long").table }
        assertFailsWith<IllegalStateException> { UuidWithWrongTable("uuid").table }
    }

    @Test
    fun `does not use a superclass companion as the concrete class table`() {
        assertSame(ParentRecord, ParentRecord("parent").table)
        assertFailsWith<IllegalStateException> { ChildRecord("child").table }
    }

    @Test
    fun `table lookup is excluded from default JSON and string rendering`() {
        assertEquals("{\"label\":\"long\"}", LongRecord("long").toJsonObject().toJsonString())
        assertEquals("{\"label\":\"uuid\"}", UuidRecord("uuid").toJsonObject().toJsonString())
        assertEquals("SimpleData(name=long, score=1)", SimpleData("long", 1).toString())
        assertFalse(SimpleData("long", 1).toJsonObject().containsKey("table"))
        assertFalse(UuidSimpleData("uuid", 1).toJsonObject().containsKey("table"))
    }
}
