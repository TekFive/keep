package org.tekfive.keep.audit

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnWithTransform
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.keep.data.ColumnGroup
import org.tekfive.keep.data.HasAssociatedTables
import org.tekfive.keep.data.UuidData
import org.tekfive.keep.data.UuidDataTable
import org.tekfive.keep.data.dataProperty
import org.tekfive.keep.db.dbTransactionInstant
import org.tekfive.keep.encryption.EncryptedColumnType
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

/**
 * Explicit writer-aware persistence with stable property IDs, provenance, and encrypted history.
 * Application types supply their state binding and writer codec. Direct Exposed/JDBC writes and
 * database cascades remain outside this API; a database trigger would be needed to cover those writes.
 */
abstract class AuditedUuidTable<D : UuidData, W : Any>(
    name: String,
    private val writerCodec: AuditWriterCodec<W>,
) : UuidDataTable<D>(name), HasAssociatedTables {
    protected abstract val auditState: AuditStateBinding<D, W>
    open val parent: AuditedParent<D, W>? = null

    private val propertyIds = linkedMapOf<String, UUID>()
    private val properties = linkedMapOf<UUID, KProperty1<*, *>>()
    private val secrets = mutableSetOf<String>()
    private val ignored = mutableSetOf<String>()
    private val excluded = mutableSetOf<String>()
    private val childCollections = linkedMapOf<UUID, AuditedChildren<W>>()
    private val contributors = linkedMapOf<String, AuditValueContributor<D>>()

    @Volatile
    private var mappingChecked = false

    val snapshotTable: AuditSnapshotTable<W> by lazy {
        val owner = parent
        if (owner == null) {
            AuditSnapshotTable("${tableName}_snapshots", writerCodec)
        } else {
            check(owner.children.table !== this) { "$tableName cannot be its own audit parent." }
            check(owner.children.table.parent == null) { "$tableName cannot be a child of a child table." }
            owner.children.table.snapshotTable
        }
    }

    override val associatedTables: List<Table>
        get() = if (parent == null) listOf(snapshotTable) else emptyList()

    protected fun <T> Column<T>.audited(propertyId: String): Column<T> = apply {
        register(property(this), propertyId)
    }

    protected fun <G : ColumnGroup<*>> G.audited(propertyId: String): G = apply {
        register(groupProperty(this), propertyId)
    }

    /** The change is audited, but no snapshot keeps this encrypted property's value. */
    protected fun <T> Column<T>.secret(propertyId: String): Column<T> = apply {
        audited(propertyId)
        secrets.add(key(property(this).name))
    }

    protected fun <G : ColumnGroup<*>> G.secret(propertyId: String): G = apply {
        audited(propertyId)
        secrets.add(key(groupProperty(this).name))
    }

    /** Explicitly excludes a derived/bookkeeping property from change attribution. */
    protected fun <T> Column<T>.unaudited(excludeFromSnapshot: Boolean = false): Column<T> = apply {
        ignore(property(this), excludeFromSnapshot)
    }

    protected fun <G : ColumnGroup<*>> G.unaudited(excludeFromSnapshot: Boolean = false): G = apply {
        ignore(groupProperty(this), excludeFromSnapshot)
    }

    /** The contributor owns the individual value IDs instead of assigning one ID to this whole column. */
    protected fun <T> Column<T>.contributedBy(contributor: AuditValueContributor<D>): Column<T> = apply {
        val property = property(this)
        checkUnregistered(property)
        require(property is KMutableProperty1<*, *>) { "A contributed property must be mutable." }
        contributors[key(property.name)] = contributor
    }

    protected fun children(name: String, propertyId: String): AuditedChildren<W> {
        checkDeclarationsOpen()
        require(name.isNotBlank() && childCollections.values.none { it.name == name }) {
            "A child collection needs a unique nonblank name."
        }
        val id = parseId(propertyId)
        requireUnusedId(id)
        return AuditedChildren(this, id, name).also { childCollections[id] = it }
    }

    fun propertyAuditId(property: KProperty1<in D, *>): UUID =
        requireNotNull(propertyIds[key(property.name)]) { "$tableName.${property.name} has no audited id." }

    fun propertyAuditId(column: Column<*>): UUID =
        requireNotNull(propertyIds[key(property(column).name)]) { "${column.name} has no audited id." }

    fun propertyName(propertyId: UUID): String? = properties[propertyId]?.name
        ?: childCollections[propertyId]?.name
        ?: contributors.values.firstNotNullOfOrNull { it.propertyName(propertyId) }

    /** Validate all declarations after construction, preferably while assembling the application's schema. */
    @Synchronized
    fun checkPropertyMapping() {
        if (mappingChecked) return
        validateMapping()
        val stateNames = setOf(key(auditState.createdBy.name), key(auditState.provenances.name))
        check(stateNames.size == 2) { "Creation attribution and provenance must be separate properties." }
        for (stateName in stateNames) {
            check(stateName in columnPropertyMap || stateName in columnGroupPropertyMap) {
                "$tableName.$stateName is not mapped."
            }
            check(stateName !in propertyIds && stateName !in contributors && stateName !in ignored) {
                "$tableName.$stateName is managed by the audit state binding."
            }
        }
        for ((name, property) in varProperties) {
            check(key(name) in propertyIds || key(name) in ignored || key(name) in contributors || key(name) in stateNames) {
                "$tableName.${property.name} needs an audited id or an explicit exclusion."
            }
        }
        for ((name, id) in propertyIds) {
            check(properties[id] in varProperties.values) { "$tableName.$name is not a mapped mutable property." }
        }
        for ((name, column) in columnPropertyMap) checkEncryption(name, listOf(column))
        for ((name, group) in columnGroupPropertyMap) checkEncryption(name, group.columns)
        val owner = parent
        if (owner != null) {
            check(owner.children.table !== this && owner.children.table.parent == null) {
                "Audited child histories must have one distinct root table."
            }
            check(key(owner.parentId.name) in columnPropertyMap) { "The audit parent id must be mapped on $tableName." }
        }
        mappingChecked = true
    }

    override fun map(row: ResultRow): D = super.map(row).also { data ->
        contributors.values.forEach { it.loaded(data) }
    }

    /** Hooks execute inside the audited transaction. They must not commit or roll back. */
    protected open fun beforeSave(data: D, writer: W) {}

    /** Keep custom representations, using the mapped property names for secret and excluded fields. */
    protected open fun serializeSnapshot(data: D): JsonObject = defaultAuditSnapshot(
        data, auditState, writerCodec, secrets + excluded + key(auditState.provenances.name),
    )

    fun save(data: D, writer: W, details: String? = null): Boolean = auditTransaction {
        persist(data, writer, details, creationId = null)
    }

    /** Creates a row with a selected ID, retaining ordinary creation attribution and history. */
    fun create(data: D, id: UUID, writer: W, details: String? = null): D = auditTransaction {
        check(!data.linkedToDb) { "An explicit creation id requires a new record." }
        persist(data, writer, details, id)
        data
    }

    fun remove(data: D, writer: W, details: String? = null) = auditTransaction {
        checkPropertyMapping()
        check(data.linkedToDb) { "Cannot remove an unstored record." }
        val stored = requireStored(data.id)
        fileSnapshot(stored, writer, emptySet(), true, details, dbTransactionInstant())
        super.delete(stored)
        data.unlinkFromDB()
    }

    /** Locks and edits the current stored row, useful for a pin set or another concurrent read/modify/write. */
    fun edit(dataId: UUID, writer: W, details: String? = null, change: (D) -> Unit): Boolean = auditTransaction {
        checkPropertyMapping()
        val stored = requireStored(dataId)
        change(stored)
        persist(stored, writer, details, creationId = null)
    }

    /** Writer-required removal of a selected set. Database cascades do not create additional snapshots. */
    fun removeAll(ids: Collection<UUID>, writer: W, details: String? = null): Int = auditTransaction {
        checkPropertyMapping()
        val rows = lockRows(ids)
        val at = dbTransactionInstant()
        for (row in rows) {
            fileSnapshot(row, writer, emptySet(), true, details, at)
            super.delete(row)
        }
        rows.size
    }

    /**
     * A controlled extension for reference moves. Locks all selected rows and snapshots each after [mutation].
     * The application must restrict its SQL mutation to these IDs and this table. Rows cannot be deleted here.
     * Existing value provenance is preserved unless [updateProvenance] is explicitly requested.
     */
    protected fun mutateAudited(
        ids: Collection<UUID>,
        writer: W,
        propertyChanges: Set<UUID>,
        details: String? = null,
        updateProvenance: Boolean = false,
        mutation: (List<D>) -> Unit,
    ): Int = auditTransaction {
        checkPropertyMapping()
        require(propertyChanges.all { it in properties }) { "Mutation ids must name declared audited properties." }
        val rows = lockRows(ids)
        val before = rows.associate { it.id to auditState.provenances.get(it).toList() }
        val creators = rows.associate { it.id to auditState.createdBy.get(it) }
        val at = dbTransactionInstant()
        mutation(rows)
        for (row in rows) {
            val stored = requireStored(row.id)
            auditState.createdBy.set(stored, creators.getValue(row.id))
            auditState.provenances.set(stored, if (updateProvenance) {
                withWrites(before.getValue(row.id), propertyChanges, writer, at)
            } else before.getValue(row.id))
            super.update(stored)
            fileSnapshot(requireStored(row.id), writer, propertyChanges, false, details, at)
        }
        rows.size
    }

    final override fun save(data: D): D = writerRequired()
    final override fun create(data: D): D = writerRequired()
    final override fun create(data: D, id: UUID): D = writerRequired()
    final override fun update(data: D): D = writerRequired()
    final override fun update(data: D, column: Column<*>, vararg additionalColumns: Column<*>): D = writerRequired()
    final override fun delete(data: D): Unit = writerRequired()
    final override fun <T> deleteByColumnValue(value: T, column: Column<T>): Int = writerRequired()

    private fun writerRequired(): Nothing =
        throw UnsupportedOperationException("$tableName requires an audit writer; use save, edit, or remove.")

    private fun persist(data: D, writer: W, details: String?, creationId: UUID?): Boolean {
        checkPropertyMapping()
        beforeSave(data, writer)
        val at = dbTransactionInstant()
        if (!data.linkedToDb) {
            auditState.createdBy.set(data, writer)
            val changes = properties.filterValues { !isEmpty(it.getter.call(data)) }.keys.toMutableSet()
            addContributedChanges(changes, contributors.mapValues { it.value.initialIds(data) })
            // Creation must not accept caller-provided provenance for values that were never written.
            auditState.provenances.set(data, withWrites(emptyList(), changes, writer, at))
            if (creationId == null) super.create(data) else super.create(data, creationId)
            fileSnapshot(requireStored(data.id), writer, changes, false, details, at)
            contributors.values.forEach { it.saved(data) }
            return true
        }
        if (!data.isDirty) return false
        val stored = requireStored(data.id)
        auditState.createdBy.set(data, auditState.createdBy.get(stored))
        val changes = data.dirtyProperties.mapNotNull { propertyIds[key(it)] }.toMutableSet()
        val contributed = contributors.mapValues { it.value.changedIds(data) }
        addContributedChanges(changes, contributed)
        for ((name, contributor) in contributors) {
            if (data.dirtyProperties.any { key(it) == name }) contributor.merge(data, stored, contributed.getValue(name))
        }
        auditState.provenances.set(data, withWrites(auditState.provenances.get(stored), changes, writer, at))
        super.update(data)
        if (changes.isNotEmpty()) fileSnapshot(requireStored(data.id), writer, changes, false, details, at)
        contributors.values.forEach { it.saved(data) }
        return true
    }

    private fun addContributedChanges(changes: MutableSet<UUID>, contributions: Map<String, Set<UUID>>) {
        val used = mutableSetOf<UUID>()
        for ((name, ids) in contributions) for (id in ids) {
            require(id !in properties && id !in childCollections && used.add(id)) {
                "$tableName.$name returned a conflicting audited id $id."
            }
            changes.add(id)
        }
    }

    private fun withWrites(
        existing: List<PropertyProvenance<W>>, ids: Set<UUID>, writer: W, at: Instant,
    ): List<PropertyProvenance<W>> = existing.filter { it.propertyId !in ids } +
        ids.sorted().map { PropertyProvenance(it, writer, at) }

    private fun fileSnapshot(
        data: D, writer: W, changes: Set<UUID>, removed: Boolean, details: String?, at: Instant,
    ) {
        // Detach before removing fields; a custom serializer may return an object held by the record.
        val json = serializeSnapshot(data).toJsonString().asRequiredJsonObject()
        val omitted = secrets + excluded + key(auditState.provenances.name)
        for (name in omitted) {
            json.remove(name)
            json.remove("_$name")
        }
        val owner = parent
        snapshotTable.append(
            dataId = owner?.parentId?.get(data) ?: data.id,
            writer = writer,
            data = json,
            propertyChanges = changes,
            details = details,
            childPropertyId = owner?.children?.propertyId,
            childId = if (owner == null) null else data.id,
            removed = removed,
            createdAt = at,
        )
    }

    private fun requireStored(dataId: UUID): D = checkNotNull(
        selectAll().where(id eq dataId).forUpdate().firstOrNull()?.let(::map),
    ) { "$tableName row $dataId is not stored." }

    private fun lockRows(ids: Collection<UUID>): List<D> {
        if (ids.isEmpty()) return emptyList()
        return selectAll().where(id inList ids.distinct()).orderBy(id).forUpdate().map(::map)
    }

    private fun checkEncryption(name: String, columns: List<Column<*>>) {
        val encrypted = columns.any(::isEncrypted)
        check(!encrypted || key(name) in secrets) { "$tableName.$name is encrypted and must be declared secret." }
        check(key(name) !in secrets || columns.all(::isEncrypted)) {
            "$tableName.$name is secret and all of its columns must be encrypted."
        }
    }

    private fun isEncrypted(column: Column<*>): Boolean = when (val type = column.columnType) {
        is ColumnWithTransform<*, *> -> type.originalColumnType is EncryptedColumnType<*>
        else -> type is EncryptedColumnType<*>
    }

    private fun property(column: Column<*>): KProperty1<*, *> {
        require(column.table === this) { "An audited column must belong to $tableName." }
        return requireNotNull(column.dataProperty) { "Declare ${column.name} with a property reference." }
    }

    private fun groupProperty(group: ColumnGroup<*>): KProperty1<*, *> =
        requireNotNull(columnGroupProperties[group]) { "Bind the column group to a property on $tableName first." }

    private fun register(property: KProperty1<*, *>, propertyId: String) {
        checkUnregistered(property)
        require(property is KMutableProperty1<*, *>) { "Only mutable properties need an audited id." }
        val id = parseId(propertyId)
        requireUnusedId(id)
        propertyIds[key(property.name)] = id
        properties[id] = property
    }

    private fun ignore(property: KProperty1<*, *>, exclude: Boolean) {
        checkUnregistered(property)
        ignored.add(key(property.name))
        if (exclude) excluded.add(key(property.name))
    }

    private fun checkUnregistered(property: KProperty1<*, *>) {
        checkDeclarationsOpen()
        val name = key(property.name)
        require(name !in propertyIds && name !in ignored && name !in contributors) {
            "$tableName.$name already has an audit declaration."
        }
    }

    private fun checkDeclarationsOpen() = check(!mappingChecked) { "Audit declarations are already validated." }

    private fun requireUnusedId(id: UUID) = require(id !in properties && id !in childCollections) {
        "Audited id $id is already used on $tableName."
    }

    private fun parseId(value: String): UUID = UUID.fromString(value).also {
        require(it.toString() == value) { "Audited ids must be canonical UUID strings." }
    }

    private fun key(name: String): String = name.removePrefix("_")

    private fun isEmpty(value: Any?): Boolean = when (value) {
        null -> true
        is CharSequence -> value.isBlank()
        is Collection<*> -> value.isEmpty()
        else -> false
    }
}
