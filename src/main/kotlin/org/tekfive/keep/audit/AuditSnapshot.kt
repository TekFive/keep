package org.tekfive.keep.audit

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.indexWithStandardName
import org.tekfive.keep.data.instant
import org.tekfive.keep.encryption.encryptedJsonObject
import org.tekfive.keep.utils.FilteredUpdateStatement
import java.time.Instant
import java.util.UUID

/** A root or child row as stored after a write, or immediately before removal. */
data class AuditSnapshot<W : Any>(
    val id: Long,
    val dataId: UUID,
    val writer: W,
    val data: JsonObject,
    val propertyChanges: List<UUID>,
    val details: String?,
    val childPropertyId: UUID?,
    val childId: UUID?,
    val removed: Boolean,
    val createdAt: Instant,
)

/**
 * One encrypted snapshot table per root audited table. Identity columns deliberately have no foreign keys:
 * history survives deletion. Reads require a transaction, like other KEEP table queries.
 * Generic writer values are mapped explicitly instead of using DataTable's record-class reflection.
 */
class AuditSnapshotTable<W : Any>(name: String, codec: AuditWriterCodec<W>) : Table(name) {
    val id = long("id").autoIncrement(DataTable.DeaultSequenceName)
    override val primaryKey = PrimaryKey(id)
    val dataId = javaUUID("data_id").indexWithStandardName()
    val writer = codec.columns(this)
    val data = encryptedJsonObject("data")
    val propertyChanges = array("property_changes", UUIDColumnType())
    val details = text("details").nullable()
    val childPropertyId = javaUUID("child_property_id").nullable()
    val childId = javaUUID("child_id").nullable()
    val removed = bool("removed")
    val createdAt = instant("created_at")

    internal fun append(
        dataId: UUID,
        writer: W,
        data: JsonObject,
        propertyChanges: Collection<UUID>,
        details: String?,
        childPropertyId: UUID?,
        childId: UUID?,
        removed: Boolean,
        createdAt: Instant,
    ) {
        require((childPropertyId == null) == (childId == null)) { "A child snapshot needs both child identifiers." }
        insert { statement ->
            statement[this.dataId] = dataId
            this.writer.mapColumns(writer, FilteredUpdateStatement(statement))
            statement[this.data] = data
            statement[this.propertyChanges] = propertyChanges.distinct().sorted()
            statement[this.details] = details
            statement[this.childPropertyId] = childPropertyId
            statement[this.childId] = childId
            statement[this.removed] = removed
            statement[this.createdAt] = createdAt
        }
    }

    fun map(row: ResultRow): AuditSnapshot<W> = AuditSnapshot(
        row[id], row[dataId], writer.map(row), row[data], row[propertyChanges], row[details],
        row[childPropertyId], row[childId], row[removed], row[createdAt],
    )

    fun findHistory(dataId: UUID): List<AuditSnapshot<W>> = selectAll().where(this.dataId eq dataId)
        .orderBy(id to SortOrder.ASC).map(::map)

    fun countHistory(dataId: UUID): Long = selectAll().where(this.dataId eq dataId).count()

    fun findHistoryPage(dataId: UUID, offset: Long, limit: Int): List<AuditSnapshot<W>> {
        require(offset >= 0) { "History offset must be nonnegative." }
        require(limit > 0) { "History limit must be positive." }
        return selectAll().where(this.dataId eq dataId)
            .orderBy(id to SortOrder.DESC)
            .limit(limit).offset(offset).map(::map)
    }

    fun firstSnapshot(dataId: UUID): AuditSnapshot<W>? =
        findFirst((this.dataId eq dataId) and childId.isNull(), SortOrder.ASC)

    fun lastSnapshot(dataId: UUID): AuditSnapshot<W>? =
        findFirst((this.dataId eq dataId) and childId.isNull(), SortOrder.DESC)

    fun previousSnapshot(snapshot: AuditSnapshot<W>): AuditSnapshot<W>? {
        val sameChild = snapshot.childId?.let { childId eq it } ?: childId.isNull()
        val sameCollection = snapshot.childPropertyId?.let { childPropertyId eq it } ?: childPropertyId.isNull()
        // A transaction can start before another writer but acquire the row lock afterward.
        // The sequence follows snapshot insertion; transaction timestamps do not.
        val earlier = id less snapshot.id
        return findFirst((dataId eq snapshot.dataId) and sameChild and sameCollection and earlier, SortOrder.DESC)
    }

    /**
     * The last addition of each current member in an audited collection, useful for a pin set.
     * Only snapshots that changed [propertyId] have their payloads decrypted. The application supplies
     * the member extraction so JSON names and collection semantics remain outside KEEP.
     */
    fun findMembershipWriters(
        dataId: UUID,
        propertyId: UUID,
        members: (JsonObject) -> Set<UUID>,
    ): Map<UUID, AuditSnapshot<W>> {
        val ids = select(id, propertyChanges).where((this.dataId eq dataId) and childId.isNull())
            .filter { propertyId in it[propertyChanges] }.map { it[id] }
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<UUID, AuditSnapshot<W>>()
        for (snapshot in selectAll().where(id inList ids)
            .orderBy(id to SortOrder.ASC).map(::map)) {
            val current = members(snapshot.data)
            result.keys.retainAll(current)
            for (member in current) result.putIfAbsent(member, snapshot)
        }
        return result
    }

    private fun findFirst(condition: Op<Boolean>, order: SortOrder): AuditSnapshot<W>? =
        selectAll().where(condition).orderBy(id to order).limit(1).firstOrNull()?.let(::map)
}
