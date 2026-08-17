package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.tekfive.keep.db.db
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

abstract class DatabaseTupleCache<D : Data>(
    protected val tuple: DataTuple<D>,
    private val cachePredicate: Op<Boolean>? = null,
) {
    abstract val maxCachedRows: Int

    abstract val maxCacheSeconds: Int

    private class Entry<D>(
        val value: D,
        val cachedAt: Long,
        val insertionOrder: Long,
    )

    private val store = ConcurrentHashMap<Long, Entry<D>>()
    private val insertionSequence = AtomicLong()

    val size: Int get() = store.size

    operator fun get(id: Long): D {
        return find(id)
            ?: throw NoSuchElementException("No ${tuple.dataClass.simpleName} found with id $id")
    }

    fun find(id: Long): D? {
        if (maxCachedRows <= 0) return fetchFromDb(id)
        val entry = store[id]
        if (entry != null && !isExpired(entry)) return entry.value
        if (entry != null) store.remove(id)
        return fetchAndCache(id)
    }

    operator fun set(id: Long, value: D) {
        if (maxCachedRows <= 0) return
        store[id] = newEntry(value)
        evictIfNeeded()
    }

    fun invalidate(id: Long) {
        store.remove(id)
    }

    fun clear() {
        store.clear()
    }

    protected open fun fetchFromDb(id: Long): D? {
        var predict = tuple.id eq id
        if (cachePredicate != null) {
            predict = predict and cachePredicate
        }

        return tuple.findByUnique(predict)
    }

    private fun fetchAndCache(id: Long): D? {
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

    private fun isExpired(entry: Entry<D>): Boolean {
        return System.currentTimeMillis() - entry.cachedAt > maxCacheSeconds * 1000L
    }

    private fun evictIfNeeded() {
        val max = maxCachedRows
        if (store.size <= max) return

        // Remove expired entries first
        val now = System.currentTimeMillis()
        val maxAgeMs = maxCacheSeconds * 1000L
        store.entries.removeIf { now - it.value.cachedAt > maxAgeMs }

        // If still over limit, remove oldest entries
        while (store.size > max) {
            val oldest = store.entries.minByOrNull { it.value.insertionOrder } ?: break
            store.remove(oldest.key, oldest.value)
        }
    }
}
