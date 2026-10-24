package org.tekfive.keep.audit

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.tekfive.jfk.JsonArray
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.db.db
import org.tekfive.keep.db.dbCommit
import org.tekfive.keep.encryption.DatabaseEncryptionProvider
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Uses an explicitly supplied existing PostgreSQL instance; never starts a container. */
@EnabledIfEnvironmentVariable(named = "KEEP_AUDIT_TEST_JDBC_URL", matches = ".+")
class AuditPersistenceIntegrationTest {
    companion object {
        private lateinit var database: Database
        private val schema = "keep_audit_test_" + UUID.randomUUID().toString().replace("-", "")

        private fun connection() = DriverManager.getConnection(
            System.getenv("KEEP_AUDIT_TEST_JDBC_URL"),
            System.getenv("KEEP_AUDIT_TEST_JDBC_USER") ?: "",
            System.getenv("KEEP_AUDIT_TEST_JDBC_PASSWORD") ?: "",
        )

        @JvmStatic @BeforeAll
        fun setup(@TempDir directory: Path) {
            initializeAuditTestEncryption(directory)
            connection().use { connection -> connection.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
            database = Database.connect({ connection().apply { this.schema = Companion.schema } })
        }

        @JvmStatic @AfterAll
        fun cleanup() {
            try {
                connection().use { connection ->
                    connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
                }
            } finally {
                DatabaseEncryptionProvider.resetForTesting()
            }
        }
    }

    private lateinit var roots: AuditRows
    private lateinit var children: ChildRows

    @BeforeEach
    fun createTables() {
        roots = AuditRows("audit_records")
        children = ChildRows("audit_children", roots.childRows)
        transaction(database) {
            exec("CREATE SEQUENCE IF NOT EXISTS globalid")
            SchemaUtils.create(roots, roots.snapshotTable, children)
        }
    }

    @AfterEach
    fun dropTables() = transaction(database) { SchemaUtils.drop(children, roots.snapshotTable, roots) }

    private fun create(name: String = "initial", secret: String? = null): AuditRow = transaction(database) {
        AuditRow(name, secret = secret).also { roots.save(it, creator) }
    }

    private fun values(first: String, second: String): JsonObject = JsonObject().apply {
        this[FIRST_VALUE.toString()] = first
        this[SECOND_VALUE.toString()] = second
    }

    @Test
    fun `creation stores provenance and encrypted redacted history with custom serialization`() {
        val row = create(secret = "dummy-credential")
        transaction(database) {
            val stored = roots.getById(row.id)
            assertEquals(creator, stored.createdBy)
            assertEquals(setOf(NAME_ID, SECRET_ID), stored.provenances.map { it.propertyId }.toSet())
            assertTrue(stored.provenances.all { it.writer == creator })
            val snapshot = assertNotNull(roots.snapshotTable.firstSnapshot(row.id))
            assertEquals(creator, snapshot.writer)
            assertEquals(setOf(NAME_ID, SECRET_ID), snapshot.propertyChanges.toSet())
            assertEquals("initial", snapshot.data["name"].string)
            assertEquals("kept", snapshot.data["custom"].string)
            assertFalse("secret" in snapshot.data.entries)
            assertFalse("provenances" in snapshot.data.entries)
            assertFalse("calculatedLabel" in snapshot.data.entries)
            assertNull(snapshot.childId)
            assertNull(roots.snapshotTable.previousSnapshot(snapshot))
            exec("SELECT data FROM audit_records_snapshots") { result ->
                assertTrue(result.next())
                val bytes = result.getBytes(1)
                assertFalse(String(bytes).contains("initial"))
                assertFalse(String(bytes).contains("dummy-credential"))
            }
        }
    }

    @Test
    fun `no-op save creates no history and creation attribution cannot be rewritten`() {
        val row = create()
        transaction(database) {
            val stored = roots.getById(row.id)
            assertFalse(roots.save(stored, editor))
            assertEquals(1L, roots.snapshotTable.countHistory(row.id))
            stored.name = "changed"
            stored.createdBy = editor
            stored.provenances = emptyList()
            assertTrue(roots.save(stored, editor))
            val fresh = roots.getById(row.id)
            assertEquals(creator, fresh.createdBy)
            assertEquals(editor, fresh.provenances.single { it.propertyId == NAME_ID }.writer)
            assertEquals(2L, roots.snapshotTable.countHistory(row.id))
        }
    }

    @Test
    fun `concurrent stale records preserve the other writer's columns and provenance`() {
        val initial = create()
        val staleRead = CountDownLatch(1)
        val committed = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit {
                try {
                    check(staleRead.await(10, TimeUnit.SECONDS))
                    transaction(database) {
                        val row = roots.getById(initial.id)
                        row.note = "first writer"
                        roots.save(row, creator)
                    }
                } finally { committed.countDown() }
            }
            val second = pool.submit {
                transaction(database) {
                    val stale = roots.getById(initial.id)
                    // Ensure this transaction's timestamp precedes the first writer's,
                    // even though its row write must appear later in history.
                    exec("SELECT pg_sleep(0.02)")
                    staleRead.countDown()
                    check(committed.await(10, TimeUnit.SECONDS))
                    stale.name = "second writer"
                    roots.save(stale, editor)
                }
            }
            first.get(20, TimeUnit.SECONDS)
            second.get(20, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
        transaction(database) {
            val stored = roots.getById(initial.id)
            assertEquals("first writer", stored.note)
            assertEquals("second writer", stored.name)
            assertEquals(creator, stored.provenances.single { it.propertyId == NOTE_ID }.writer)
            assertEquals(editor, stored.provenances.single { it.propertyId == NAME_ID }.writer)
            val last = assertNotNull(roots.snapshotTable.lastSnapshot(initial.id))
            assertEquals("first writer", last.data["note"].string)
            assertEquals(listOf(NAME_ID), last.propertyChanges)
            val previous = assertNotNull(roots.snapshotTable.previousSnapshot(last))
            assertEquals(listOf(NOTE_ID), previous.propertyChanges)
            assertTrue(last.createdAt.isBefore(previous.createdAt))
            assertEquals(last.id, roots.snapshotTable.findHistory(initial.id).last().id)
            assertEquals(last.id, roots.snapshotTable.findHistoryPage(initial.id, 0, 1).single().id)
        }
    }

    @Test
    fun `contributors merge individual values from a stale record and reset their baseline`() {
        val initial = transaction(database) {
            AuditRow("dynamic", values = values("a", "b")).also { roots.save(it, creator) }
        }
        val stale = transaction(database) { roots.getById(initial.id) }
        transaction(database) {
            val current = roots.getById(initial.id)
            current.values = values("a", "new-b")
            roots.save(current, creator)
        }
        transaction(database) {
            stale.values = values("new-a", "b")
            roots.save(stale, editor)
            assertFalse(roots.save(stale, editor))
            val stored = roots.getById(initial.id)
            assertEquals("new-a", stored.values[FIRST_VALUE.toString()].string)
            assertEquals("new-b", stored.values[SECOND_VALUE.toString()].string)
            assertEquals(creator, stored.provenances.single { it.propertyId == SECOND_VALUE }.writer)
            assertEquals(editor, stored.provenances.single { it.propertyId == FIRST_VALUE }.writer)
            assertEquals(listOf(FIRST_VALUE), roots.snapshotTable.lastSnapshot(initial.id)?.propertyChanges)
        }
    }

    @Test
    fun `caught serialization and history SQL failures roll back only their audited mutation`() {
        transaction(database) {
            val retained = AuditRow("retained")
            roots.save(retained, creator)
            roots.failSnapshot = true
            val rejectedId = UUID.randomUUID()
            assertFailsWith<IllegalStateException> { roots.create(AuditRow("rejected"), rejectedId, editor) }
            roots.failSnapshot = false
            assertNull(roots.findById(rejectedId))
            assertEquals(1L, roots.snapshotTable.countHistory(retained.id))
            val updated = roots.getById(retained.id)
            updated.name = "rejected update"
            exec("ALTER TABLE audit_records_snapshots ADD CONSTRAINT reject_details CHECK (details <> 'reject')")
            assertFails { roots.save(updated, editor, details = "reject") }
            assertEquals("retained", roots.getById(retained.id).name)
            assertEquals(1L, roots.snapshotTable.countHistory(retained.id))
            roots.save(AuditRow("after failure"), editor)
        }
        transaction(database) { assertEquals(2L, roots.selectAll().count()) }
    }

    @Test
    fun `outer rollback removes both record and history`() {
        val id = UUID.randomUUID()
        transaction(database) {
            roots.create(AuditRow("rolled back"), id, creator)
            rollback()
        }
        transaction(database) {
            assertNull(roots.findById(id))
            assertEquals(0L, roots.snapshotTable.countHistory(id))
        }
    }

    @Test
    fun `audit hooks cannot commit the enclosing transaction`() {
        transaction(database) {
            roots.before = { dbCommit() }
            assertFailsWith<IllegalStateException> { roots.save(AuditRow("rejected"), creator) }
            roots.before = null
            assertEquals(0L, roots.selectAll().count())
            roots.save(AuditRow("allowed"), creator)
        }
    }

    @Test
    fun `cached misses and rows do not replace fresh snapshots or survive audit writes`() = transaction(database) {
        db(cache = true) {
            val id = UUID.randomUUID()
            assertNull(roots.findById(id))
            roots.create(AuditRow("created"), id, creator)
            assertEquals("created", roots.getById(id).name)
            val detached = roots.selectAll().where(roots.id eq id).single().let(roots::map)
            detached.name = "fresh"
            roots.save(detached, editor)
            assertEquals("fresh", roots.getById(id).name)
            assertEquals("fresh", roots.snapshotTable.lastSnapshot(id)?.data?.get("name")?.string)
        }
    }

    @Test
    fun `child changes share root history and removal preserves historical values`() = transaction(database) {
        val root = AuditRow("parent").also { roots.save(it, creator) }
        val child = ChildRow(root.id, "child").also { children.save(it, creator) }
        child.name = "edited child"
        children.save(child, editor)
        children.remove(child, editor)
        val history = roots.snapshotTable.findHistory(root.id)
        assertEquals(4, history.size)
        assertTrue(history.drop(1).all { it.childPropertyId == CHILDREN_ID })
        assertEquals("edited child", history.last().data["name"].string)
        assertTrue(history.last().removed)
        assertEquals(history[2], roots.snapshotTable.previousSnapshot(history[3]))
        assertEquals(history.first(), roots.snapshotTable.lastSnapshot(root.id))
        assertEquals(listOf(history[2], history[1]), roots.snapshotTable.findHistoryPage(root.id, 1, 2))
        assertFailsWith<IllegalArgumentException> { roots.snapshotTable.findHistoryPage(root.id, -1, 2) }
        assertFailsWith<IllegalArgumentException> { roots.snapshotTable.findHistoryPage(root.id, 0, 0) }
        val rootId = root.id
        roots.remove(root, editor)
        assertNull(roots.findById(rootId))
        assertEquals(5L, roots.snapshotTable.countHistory(rootId))
    }

    @Test
    fun `locked edits and membership history distinguish unpin and repin`() {
        val initial = create()
        transaction(database) {
            roots.edit(initial.id, creator) { it.pins = listOf(NAME_ID) }
            roots.edit(initial.id, editor) { it.pins = emptyList() }
            roots.edit(initial.id, editor) { it.pins = listOf(NAME_ID, NOTE_ID) }
            val writers = roots.snapshotTable.findMembershipWriters(initial.id, PINS_ID) { json ->
                (json["pins"] as JsonArray).items.mapTo(mutableSetOf()) { UUID.fromString(it.string) }
            }
            assertEquals(setOf(NAME_ID, NOTE_ID), writers.keys)
            assertTrue(writers.values.all { it.writer == editor })
        }
    }

    @Test
    fun `controlled reference-style mutations preserve value provenance and record fresh history`() {
        val row = create()
        transaction(database) {
            roots.renameWithoutChangingProvenance(row.id, editor) { locked ->
                roots.update({ roots.id inList locked.map { it.id } }) { it[roots.name] = "renamed" }
            }
            val stored = roots.getById(row.id)
            assertEquals("renamed", stored.name)
            assertEquals(creator, stored.provenances.single { it.propertyId == NAME_ID }.writer)
            val snapshot = assertNotNull(roots.snapshotTable.lastSnapshot(row.id))
            assertEquals(editor, snapshot.writer)
            assertEquals("renamed", snapshot.data["name"].string)
        }
    }

    @Test
    fun `bulk removal requires a writer and records each selected stored row`() {
        val first = create("first")
        val second = create("second")
        val firstId = first.id
        val secondId = second.id
        transaction(database) {
            assertEquals(2, roots.removeAll(listOf(firstId, secondId, firstId, UUID.randomUUID()), editor))
            assertEquals(0L, roots.selectAll().count())
            assertTrue(assertNotNull(roots.snapshotTable.lastSnapshot(firstId)).removed)
            assertTrue(assertNotNull(roots.snapshotTable.lastSnapshot(secondId)).removed)
        }
    }
}
