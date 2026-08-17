package org.tekfive.keep.data

import org.tekfive.keep.array.setArray

// ---------------------------------------------------------------------------
// Shared test Data classes and DataTable definitions.
// ---------------------------------------------------------------------------

// -- Simple (flat) scenario: Data subclass directly extends Data --------------

class SimpleData(val name: String, var score: Int) : Data()

object SimpleTable : DataTable<SimpleData>("simple") {
    val name = varchar("name", 255)
    val score = integer("score")
}

class MutablePairData(val name: String, var score: Int, var active: Boolean) : Data()

object MutablePairTable : DataTable<MutablePairData>("mutable_pair") {
    val name = varchar("name", 255)
    val score = integer("score")
    val active = bool("active")
}

// -- Hierarchy scenario: LeafData -> BaseData -> Data -------------------------

abstract class BaseData(val name: String, var active: Boolean) : Data()

class LeafData(name: String, active: Boolean, var score: Int) : BaseData(name, active)

object HierarchyTable : DataTable<LeafData>("hierarchy") {
    val name = varchar("name", 255)
    val active = bool("active")
    val score = integer("score")
}

// -- Intermediate abstract table scenario: WidgetTable -> BaseWidgetTable -> DataTable

class WidgetData(val label: String, var quantity: Int) : Data()

abstract class BaseWidgetTable<D : Data>(name: String) : DataTable<D>(name) {
    val label = varchar("label", 255)
}

object WidgetTable : BaseWidgetTable<WidgetData>("widgets") {
    val quantity = integer("quantity")
}

// -- Join table scenario: SimpleData <-> WidgetData via a join table ----------

object SimpleWidgetJoin : DataJoinTable<SimpleData, WidgetData>(
    "simple_widget", SimpleTable, WidgetTable
)

object CustomColumnJoin : DataJoinTable<SimpleData, WidgetData>(
    "custom_join", SimpleTable, WidgetTable,
    columnAName = "sid", columnBName = "wid"
)

// -- DataEnum scenario --------------------------------------------------------

enum class Priority(override val id: Int, override val displayName: String) : DataEnum {
    LOW(10, "Low"),
    MEDIUM(20, "Medium"),
    HIGH(30, "High");
    companion object : DataEnumColumnType<Priority>()
}

enum class UnvalidatedPriority(override val id: Int, override val displayName: String) : DataEnum {
    LOW(10, "Low"),
    MEDIUM(20, "Medium"),
    HIGH(30, "High"),
}

class TaskData(val title: String, var priority: Priority) : Data()

object TaskTable : DataTable<TaskData>("tasks") {
    val title = varchar("title", 255)
    val priority = dataEnum<Priority>("priority_id")
}

// -- enumList scenario --------------------------------------------------------

class TaggedData(val label: String, var priorities: List<Priority>) : Data()

object TaggedTable : DataTable<TaggedData>("tagged") {
    val label = varchar("label", 255)
    val priorities = dataEnumList<Priority>("priority_ids")
}

// -- Set array scenarios ------------------------------------------------------

class TaggedSetData(val label: String, var priorities: Set<Priority>) : Data()

object TaggedSetTable : DataTable<TaggedSetData>("tagged_set") {
    val label = varchar("label", 255)
    val priorities = dataEnumSet<Priority>("priority_ids")
}

class StringSetData(val label: String, var tags: Set<String>) : Data()

object StringSetTable : DataTable<StringSetData>("string_set") {
    val label = varchar("label", 255)
    val tags = setArray<String>("tags")
}

// -- DataView scenario --------------------------------------------------------

class SimpleViewData(val name: String, var score: Int) : Data()

object SimpleView : DataView<SimpleViewData>("simple_view") {
    val name = varchar("name", 255)
    val score = integer("score")
}

// -- Foreign key scenario -----------------------------------------------------

class NoteData(val text: String, var simpleId: Long) : Data()

object NoteTable : DataTable<NoteData>("notes") {
    val text = varchar("text", 255)
    val simpleId = fkey("simple_id", SimpleTable)
}

// -- UUIDv7 identity scenario -------------------------------------------------

class UuidSimpleData(val name: String, var score: Int) : UuidData()

object UuidSimpleTable : UuidDataTable<UuidSimpleData>("uuid_simple"), UuidTableWithUniqueName {
    override val name = varchar("name", 255)
    val score = integer("score")
    override val customIndices = listOf("CREATE INDEX uuid_simple_score_custom_idx ON uuid_simple(score)")
}

class UuidWidgetData(val label: String, var quantity: Int) : UuidData()

object UuidWidgetTable : UuidDataTable<UuidWidgetData>("uuid_widgets") {
    val label = varchar("label", 255)
    val quantity = integer("quantity")
}

object UuidSimpleWidgetJoin : UuidDataJoinTable<UuidSimpleData, UuidWidgetData>(
    "uuid_simple_widget",
    UuidSimpleTable,
    UuidWidgetTable,
)

class UuidNoteData(val text: String, var simpleId: java.util.UUID) : UuidData()

object UuidNoteTable : UuidDataTable<UuidNoteData>("uuid_notes") {
    val text = varchar("text", 255)
    val simpleId = fkey("simple_id", UuidSimpleTable)
}

// -- Nullable list scenario: empty list maps to null on nullable columns ------

class NullableListData(val name: String, var tags: List<String>) : Data()

object NullableListTable : DataTable<NullableListData>("nullable_list") {
    val name = varchar("name", 255)
    val tags = text("tags").nullable()
}

class NullableSetData(val name: String, var tags: Set<String>) : Data()

object NullableSetTable : DataTable<NullableSetData>("nullable_set") {
    val name = varchar("name", 255)
    val tags = text("tags").nullable()
}

// -- toString test scenarios ---------------------------------------------------

class SecretData(val name: String, var sharedSecret: String, var password: String) : Data()

class KeyIdData(val credentialTypeId: String, val triggerNodeId: Long) : Data()

class JsonPropertyData(val label: String, var payload: org.tekfive.jfk.JsonObject) : Data()

class ByteArrayData(val label: String, var content: ByteArray) : Data()

class SensitiveOverrideData(val name: String, var notes: String) : Data() {
    override val propertiesNotToPrint: List<kotlin.reflect.KProperty<*>>
        get() = listOf(SensitiveOverrideData::notes)
}

// -- Mismatch scenario for init validation tests ------------------------------

class MissingColData(val a: String, var b: String) : Data()
