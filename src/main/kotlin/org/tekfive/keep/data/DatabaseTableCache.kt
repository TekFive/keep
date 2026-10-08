package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Op

/**
 * A complete, expiring table snapshot for Long IDs.
 *
 * ```kotlin
 * object CountriesCache : DatabaseTableCache<CountryData>(CountriesTable) {}
 * ```
 */
open class DatabaseTableCache<D : Data>(
    table: DataTable<D>,
    cachePredicate: Op<Boolean>? = null,
) : TypedDatabaseTableCache<Long, D>(table, cachePredicate)
