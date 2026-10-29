# Caching

[Usage guide index](README.md) · [KEEP README](../README.md)

[![PostgreSQL logo](https://www.postgresql.org/media/img/about/press/elephant.png)](https://www.postgresql.org/)

`DatabaseTableCache<D>` and `UuidDatabaseTableCache<D>` cache an entire table in one snapshot,
optionally restricted by a `cachePredicate`. They suit small reference tables that fit in memory.
Unlike `DatabaseTupleCache` and `UuidDatabaseTupleCache`, reads load all selected rows together:

```kotlin
object CountriesCache : DatabaseTableCache<CountryData>(CountriesTable) {}

val countries = CountriesCache.findAll()
val country = CountriesCache[countryId]          // Throws if absent.
val optionalCountry = CountriesCache.find(countryId)
val count = CountriesCache.size
CountriesCache.refresh()                       // Reload immediately.
CountriesCache.invalidate()                    // Reload on the next read.
CountriesCache.clear()                         // Alias for invalidate().
```

All three table-cache classes can also be constructed directly:

```kotlin
val countriesCache = DatabaseTableCache(CountriesTable)
val uuidCache = UuidDatabaseTableCache(MyUuidTable)
val typedCache = TypedDatabaseTableCache(CountriesTable)
```

The classes remain open for subclassing and custom overrides. Both ID-specific wrappers
share `TypedDatabaseTableCache<ID, D>`. Supply `cachePredicate = CountriesTable.active eq true`
to cache a filtered selection. The default loader orders rows by ID.

`maxCacheSeconds` defaults to Ack configuration `TABLE_CACHE_<TABLE_NAME>_MAX_CACHE_SECONDS`,
with a fallback of **300 seconds**. The table name includes its schema when specified, is uppercased,
and has quotes removed and punctuation replaced with underscores. For example, `countries` uses
`TABLE_CACHE_COUNTRIES_MAX_CACHE_SECONDS`; `app.countries` uses
`TABLE_CACHE_APP_COUNTRIES_MAX_CACHE_SECONDS`. The setting is exposed as
`maxCacheSecondsProperty`. You can still override `maxCacheSeconds` in a subclass.

The first read loads the snapshot. Empty results and absent IDs stay cached until the snapshot
expires or is invalidated. Expiration starts when loading finishes and does not extend on reads;
`maxCacheSeconds <= 0` disables retention. Concurrent initial or expired reads share one load,
and refresh replaces the complete snapshot atomically. Failed loads throw without publishing
partial results; expired snapshots are not served as a fallback.

Returned lists are unmodifiable, but the contained data objects are shared and may be mutable.
These caches are local to each application instance and have no automatic write invalidation.
Database loads join the caller's transaction; refresh or invalidate after a successful commit,
and avoid loading uncommitted changes into a shared cache.

## Next steps

See [transactions](database.md#transaction-utilities) for transaction-scoped caching and [tables](tables.md) for record mapping.
