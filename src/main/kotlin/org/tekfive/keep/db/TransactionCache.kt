package org.tekfive.keep.db

/**
 * Thread-local cache scoped to a [db] transaction. When enabled via `db(cache = true) { ... }`,
 * query methods on [DataTuple] (e.g. `getById`, `findById`) will read from and populate this cache,
 * avoiding repeated database hits for the same entity within a single transaction.
 *
 * The cache uses arbitrary keys, so custom query functions can also participate:
 * ```
 * val cache = TransactionCache.current
 * cache?.get<MyData>(myKey) ?: queryAndCache(myKey)
 * ```
 */
class TransactionCache internal constructor() {

    @PublishedApi
    internal val store = HashMap<Any, Any?>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: Any): T? = store[key] as? T

    fun containsKey(key: Any): Boolean = key in store

    /**
     * Returns the cached value for [key] if present (including null), or computes and caches
     * the result of [defaultValue]. Unlike [get], this correctly distinguishes between "not cached"
     * and "cached as null" — if null was explicitly stored for [key], it is returned without
     * invoking [defaultValue].
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <T> getOrPut(key: Any, defaultValue: () -> T?): T? {
        if (key in store) return store[key] as T?
        val value = defaultValue()
        store[key] = value
        return value
    }

    fun put(key: Any, value: Any?) {
        store[key] = value
    }

    fun remove(key: Any) {
        store.remove(key)
    }

    companion object {
        private val threadLocal = ThreadLocal<TransactionCache?>()

        val current: TransactionCache? get() = threadLocal.get()

        operator fun <T> invoke(block: () -> T): T {
            if (current != null) return block()
            val cache = TransactionCache()
            threadLocal.set(cache)
            return try {
                block()
            } finally {
                threadLocal.remove()
            }
        }
    }
}