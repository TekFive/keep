package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.tekfive.ack.Ack
import java.util.Collections
import java.util.Locale

/**
 * An in-process cache of every row selected from [table], optionally restricted by [cachePredicate].
 *
 * Loads lazily, including empty results, and publishes complete snapshots atomically. Concurrent
 * callers share one initial/expired load. A refresh failure leaves the previous snapshot intact
 * and propagates the failure; expired snapshots are never served as a fallback.
 *
 * Collections are unmodifiable, but their data objects are shared and may be mutable. Writes do
 * not invalidate this cache automatically. Database reads join the caller's transaction, so refresh
 * or invalidate after a successful commit to avoid publishing uncommitted data across callers.
 */
abstract class TypedDatabaseTableCache<ID : Any, D : IdentifiedData<ID>>(
    protected val table: TypedDataTuple<ID, D>,
    private val cachePredicate: Op<Boolean>? = null,
) {
    /** Per-table Ack setting, including the schema when present; defaults to five minutes. */
    val maxCacheSecondsProperty: Ack<Int> by lazy {
        val tableKey = table.tableName.replace("\"", "").uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]+"), "_").trim('_')
        Ack.int(
            "TABLE_CACHE_${tableKey}_MAX_CACHE_SECONDS",
            300,
            description = "Snapshot lifetime in seconds for ${table.tableName}. Zero or negative values disable retention.",
        )
    }

    /** Lifetime from the completion of a load. Override to replace the Ack-backed default. */
    open val maxCacheSeconds: Int get() = maxCacheSecondsProperty()

    private class Snapshot<ID, D>(val rows: List<D>, val byId: Map<ID, D>, val loadedAt: Long)

    private val loadLock = Any()
    @Volatile private var cached: Snapshot<ID, D>? = null

    /** Number of rows in the current snapshot; loads or refreshes it when needed. */
    val size: Int get() = snapshot().rows.size

    /** All selected rows, ordered by ID by the default database loader. */
    fun findAll(): List<D> = snapshot().rows

    /** Looks up an ID in the full snapshot. Missing IDs do not issue individual database queries. */
    fun find(id: ID): D? = snapshot().byId[id]

    operator fun get(id: ID): D = find(id)
        ?: throw NoSuchElementException("No row found in ${table.tableName} with id $id")

    /** Immediately reloads all selected rows, replacing the snapshot only after a successful load. */
    fun refresh(): List<D> = synchronized(loadLock) { loadAndPublish().rows }

    /** Discards the snapshot. The next read reloads the whole selection. */
    fun invalidate() = synchronized(loadLock) { cached = null }

    /** Alias for [invalidate]; does not change database rows. */
    fun clear() = invalidate()

    /** Override for a custom bulk loader. Every row must have a distinct, linked database ID. */
    protected open fun fetchFromDb(): List<D> = if (cachePredicate == null) {
        table.findAll(table.id to SortOrder.ASC)
    } else {
        table.findWhere(cachePredicate, table.id to SortOrder.ASC)
    }

    /** Monotonic time source, overridable for deterministic expiration tests. */
    protected open fun nanoTime(): Long = System.nanoTime()

    private fun isFresh(snapshot: Snapshot<ID, D>): Boolean {
        val seconds = maxCacheSeconds
        return seconds > 0 && nanoTime() - snapshot.loadedAt < seconds.toLong() * 1_000_000_000L
    }

    private fun snapshot(): Snapshot<ID, D> {
        cached?.takeIf(::isFresh)?.let { return it }
        return synchronized(loadLock) {
            cached?.takeIf(::isFresh) ?: loadAndPublish()
        }
    }

    private fun loadAndPublish(): Snapshot<ID, D> {
        val rows = ArrayList(fetchFromDb())
        val byId = LinkedHashMap<ID, D>(rows.size)
        rows.forEach { row ->
            val id = requireNotNull(row.idOrNull) { "Table cache rows must have a linked database ID" }
            require(byId.put(id, row) == null) { "Duplicate table cache ID: $id" }
        }
        val snapshot = Snapshot(Collections.unmodifiableList(rows), Collections.unmodifiableMap(byId), nanoTime())
        cached = snapshot.takeIf { maxCacheSeconds > 0 }
        return snapshot
    }
}
