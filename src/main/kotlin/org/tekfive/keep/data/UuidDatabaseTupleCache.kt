package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A bounded, expiring cache for UUID-backed tuples. */
abstract class UuidDatabaseTupleCache<D : UuidData>(
    protected val tuple: UuidDataTuple<D>,
    private val cachePredicate: Op<Boolean>? = null,
) {
    abstract val maxCachedRows: Int

    abstract val maxCacheSeconds: Int

    private class Entry<D>(
        val value: D,
        val cachedAt: Long,
        val insertionOrder: Long,
    )

    private val store = ConcurrentHashMap<UUID, Entry<D>>()
    private val insertionSequence = AtomicLong()

    val size: Int get() = store.size

    operator fun get(id: UUID): D = find(id)
        ?: throw NoSuchElementException("No ${tuple.dataClass.simpleName} found with id $id")

    fun find(id: UUID): D? {
        if (maxCachedRows <= 0) return fetchFromDb(id)
        val entry = store[id]
        if (entry != null && !isExpired(entry)) return entry.value
        if (entry != null) store.remove(id)
        return fetchAndCache(id)
    }

    operator fun set(id: UUID, value: D) {
        if (maxCachedRows <= 0) return
        store[id] = newEntry(value)
        evictIfNeeded()
    }

    fun invalidate(id: UUID) {
        store.remove(id)
    }

    fun clear() {
        store.clear()
    }

    protected open fun fetchFromDb(id: UUID): D? {
        var predicate = tuple.id eq id
        if (cachePredicate != null) predicate = predicate and cachePredicate
        return tuple.findByUnique(predicate)
    }

    private fun fetchAndCache(id: UUID): D? {
        val value = fetchFromDb(id) ?: return null
        store[id] = newEntry(value)
        evictIfNeeded()
        return value
    }

    private fun newEntry(value: D): Entry<D> = Entry(
        value = value,
        cachedAt = System.currentTimeMillis(),
        insertionOrder = insertionSequence.incrementAndGet(),
    )

    private fun isExpired(entry: Entry<D>): Boolean =
        System.currentTimeMillis() - entry.cachedAt > maxCacheSeconds * 1000L

    private fun evictIfNeeded() {
        if (store.size <= maxCachedRows) return

        val now = System.currentTimeMillis()
        val maxAgeMs = maxCacheSeconds * 1000L
        store.entries.removeIf { now - it.value.cachedAt > maxAgeMs }

        while (store.size > maxCachedRows) {
            val oldest = store.entries.minByOrNull { it.value.insertionOrder } ?: break
            store.remove(oldest.key, oldest.value)
        }
    }
}
