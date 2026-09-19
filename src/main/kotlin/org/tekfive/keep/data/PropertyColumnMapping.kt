package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import kotlin.reflect.KProperty1

/** The Data property explicitly supplied when this column was declared, if any. */
val Column<*>.dataProperty: KProperty1<*, *>?
    get() = when (val owner = table) {
        is TypedDataTuple<*, *> -> owner.columnProperties[name]
        else -> PlainTablePropertyMappings.columnProperty(this)
    }

@PublishedApi
internal fun <T> Column<T>.withDataProperty(property: KProperty1<*, *>): Column<T> = apply {
    when (val owner = table) {
        is TypedDataTuple<*, *> -> owner.bindColumnProperty(this, property)
        else -> PlainTablePropertyMappings.bind(this, property)
    }
}

internal fun Table.bindGroupProperty(group: ColumnGroup<*>, property: KProperty1<*, *>) {
    when (this) {
        is TypedDataTuple<*, *> -> bindColumnGroupProperty(group, property)
        else -> PlainTablePropertyMappings.bind(group, property)
    }
}

/** Fallback for plain Exposed tables, which cannot own KEEP metadata. KEEP tables never use this registry. */
private object PlainTablePropertyMappings {
    // Use table identity: Exposed considers distinct table instances with the same SQL name equal.
    // Key columns by SQL name so Exposed's nullable/transform copies retain their mapping.
    private val columns = WeakIdentityMap<Table, MutableMap<String, KProperty1<*, *>>>()
    private val groups = WeakIdentityMap<ColumnGroup<*>, KProperty1<*, *>>()

    @Synchronized
    fun bind(column: Column<*>, property: KProperty1<*, *>) {
        val bindings = columns[column.table] ?: mutableMapOf<String, KProperty1<*, *>>().also {
            columns[column.table] = it
        }
        val previous = bindings[column.name]
        require(previous == null || previous == property) {
            "Column '${column.name}' is already mapped to '${previous?.name}'"
        }
        bindings[column.name] = property
    }

    @Synchronized
    fun columnProperty(column: Column<*>): KProperty1<*, *>? = columns[column.table]?.get(column.name)

    @Synchronized
    fun bind(group: ColumnGroup<*>, property: KProperty1<*, *>) {
        val previous = groups[group]
        require(previous == null || previous == property) {
            "Column group is already mapped to '${previous?.name}'"
        }
        groups[group] = property
    }
}

/** Weak identity keys avoid retaining dynamically created tables or groups in the metadata registry. */
private class WeakIdentityMap<K : Any, V> {
    private val queue = ReferenceQueue<K>()
    private val entries = HashMap<Key<K>, V>()

    operator fun get(key: K): V? {
        removeCollectedKeys()
        return entries[Key(key)]
    }

    operator fun set(key: K, value: V) {
        removeCollectedKeys()
        entries[Key(key, queue)] = value
    }

    private fun removeCollectedKeys() {
        while (true) {
            val key = queue.poll() ?: return
            entries.remove(key)
        }
    }

    private class Key<K : Any>(value: K, queue: ReferenceQueue<K>? = null) : WeakReference<K>(value, queue) {
        private val identityHash = System.identityHashCode(value)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean =
            this === other || (other is Key<*> && get()?.let { it === other.get() } == true)
    }
}
