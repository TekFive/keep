package org.tekfive.keep.audit

import org.jetbrains.exposed.v1.core.Table
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.data.HasAssociatedTables
import org.tekfive.keep.data.UuidData
import org.tekfive.keep.data.UuidDataTable
import org.tekfive.keep.data.column
import org.tekfive.keep.data.withAssociatedTables
import org.tekfive.keep.encryption.DatabaseEncryptionProvider
import org.tekfive.keep.encryption.EncryptionKeysetMode
import org.tekfive.keep.encryption.KeysetIO
import org.tekfive.keep.encryption.KeysetLoader
import org.tekfive.keep.encryption.KeysetTemplate
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal fun initializeAuditTestEncryption(directory: Path) {
    DatabaseEncryptionProvider.resetForTesting()
    val path = directory.resolve("audit-test-keyset.json")
    KeysetIO.write(KeysetTemplate.generateNewKeysetHandle(), path)
    DatabaseEncryptionProvider.configure(KeysetLoader.Config(mode = EncryptionKeysetMode.PLAINTEXT, file = path))
    DatabaseEncryptionProvider.ensureInitialized()
}

class DeclarationRow(var value: String, var createdBy: TestWriter, var provenances: List<PropertyProvenance<TestWriter>>) : UuidData()

class DeclarationRows(mode: String, propertyId: String = NAME_ID.toString()) :
    AuditedUuidTable<DeclarationRow, TestWriter>("declaration_rows", TestWriterCodec) {
    val value = column(DeclarationRow::value, encrypted = mode == "encrypted" || mode.startsWith("transformed"))
        .let { if (mode.startsWith("transformed")) it.transform({ value -> value }, { value -> value }) else it }.let {
        when (mode) {
            "missing" -> it
            "secret", "transformed-secret" -> it.secret(propertyId)
            else -> it.audited(propertyId)
        }
    }
    val createdBy = column(DeclarationRow::createdBy, TestWriterCodec.columns(this))
    val provenances = auditProvenances(DeclarationRow::provenances, TestWriterCodec)
    override val auditState = AuditStateBinding(DeclarationRow::createdBy, DeclarationRow::provenances)
    fun duplicateId() = children("duplicate", NAME_ID.toString())
    fun lateDeclaration() = children("late", CHILDREN_ID.toString())
}

class AuditDeclarationTest {
    companion object {
        @JvmStatic @BeforeAll
        fun setup(@TempDir directory: Path) = initializeAuditTestEncryption(directory)
        @JvmStatic @AfterAll
        fun cleanup() = DatabaseEncryptionProvider.resetForTesting()
    }

    @Test
    fun `requires explicit audit declarations and encrypted secrets`() {
        assertFailsWith<IllegalStateException> { DeclarationRows("missing").checkPropertyMapping() }
        assertFailsWith<IllegalStateException> { DeclarationRows("encrypted").checkPropertyMapping() }
        assertFailsWith<IllegalStateException> { DeclarationRows("secret").checkPropertyMapping() }
        assertFailsWith<IllegalStateException> { DeclarationRows("transformed").checkPropertyMapping() }
        DeclarationRows("transformed-secret").checkPropertyMapping()
        DeclarationRows("ordinary").checkPropertyMapping()
        AuditRows("complete_rows").checkPropertyMapping()
    }

    @Test
    fun `stable ids reject noncanonical duplicates and late declarations`() {
        assertFailsWith<IllegalArgumentException> { DeclarationRows("ordinary", NAME_ID.toString().uppercase()) }
        assertFailsWith<IllegalArgumentException> { DeclarationRows("ordinary", "1-1-1-1-1") }
        assertFailsWith<IllegalArgumentException> { DeclarationRows("ordinary").duplicateId() }
        val table = DeclarationRows("ordinary")
        table.checkPropertyMapping()
        assertFailsWith<IllegalStateException> { table.lateDeclaration() }
    }

    @Test
    fun `all writerless CRUD entrypoints refuse writes even through the base type`() {
        val table: UuidDataTable<AuditRow> = AuditRows("writer_required")
        val row = AuditRow("new")
        val typed = table as AuditRows
        assertFailsWith<UnsupportedOperationException> { table.save(row) }
        assertFailsWith<UnsupportedOperationException> { table.create(row) }
        assertFailsWith<UnsupportedOperationException> { table.create(row, row.id) }
        assertFailsWith<UnsupportedOperationException> { table.update(row) }
        assertFailsWith<UnsupportedOperationException> { table.update(row, typed.name) }
        assertFailsWith<UnsupportedOperationException> { table.delete(row) }
        assertFailsWith<UnsupportedOperationException> { table.deleteByColumnValue("new", typed.name) }
    }

    @Test
    fun `children share root history without declaring another snapshot table`() {
        val roots = AuditRows("roots")
        val children = ChildRows("children", roots.childRows)
        roots.checkPropertyMapping()
        children.checkPropertyMapping()
        assertSame(roots.snapshotTable, children.snapshotTable)
        assertTrue(children.associatedTables.isEmpty())
        assertEquals(listOf(roots, roots.snapshotTable, children), withAssociatedTables(listOf(roots, children, roots)))
        assertEquals("name", roots.propertyName(NAME_ID))
        assertEquals("First value", roots.propertyName(FIRST_VALUE))
        assertEquals("children", roots.propertyName(CHILDREN_ID))
        val grandchild = ChildRows("grandchildren", children.childRows)
        assertFailsWith<IllegalStateException> { grandchild.checkPropertyMapping() }
        assertFailsWith<IllegalStateException> { grandchild.snapshotTable }
    }

    @Test
    fun `associated table traversal handles cycles and rejects duplicate SQL declarations`() {
        class Associated(name: String) : Table(name), HasAssociatedTables {
            override var associatedTables: List<Table> = emptyList()
        }
        val first = Associated("first")
        val second = Associated("second")
        first.associatedTables = listOf(second)
        second.associatedTables = listOf(first)
        assertEquals(listOf(first, second), withAssociatedTables(listOf(first)))
        assertFailsWith<IllegalArgumentException> { withAssociatedTables(listOf(first, Associated("first"))) }
    }

    @Test
    fun `application record serializers retain their exact representation`() {
        val row = CustomSerializedRow("precise", creator, emptyList())
        val json = defaultAuditSnapshot(
            row, AuditStateBinding(CustomSerializedRow::createdBy, CustomSerializedRow::provenances),
            TestWriterCodec, setOf("provenances"),
        )
        assertEquals("12345678901234567890.123456789", json["exact"].string)
        assertEquals("application", json["format"].string)
    }
}

class CustomSerializedRow(
    var label: String,
    var createdBy: TestWriter,
    var provenances: List<PropertyProvenance<TestWriter>>,
) : UuidData() {
    override fun toJsonObject() = JsonObject(mapOf("exact" to "12345678901234567890.123456789", "format" to "application"))
}
