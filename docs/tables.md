# Records, tables, and singleton tables

[Usage guide index](README.md) · [KEEP README](../README.md)

[![JetBrains logo](https://resources.jetbrains.com/storage/products/company/brand/logos/jetbrains.svg)](https://www.jetbrains.com/exposed/)

[![PostgreSQL logo](https://www.postgresql.org/media/img/about/press/elephant.png)](https://www.postgresql.org/)

## Record mapping and CRUD

KEEP provides `Data`, `DataTable`, `DataTuple`, and related table abstractions for mapping Kotlin objects to Exposed tables. Columns declared with a property reference map directly to that Data property; other declarations match table property names to primary constructor property names. The managed `id` column is handled by `DataTable`, so domain classes focus on business fields.

Immutable `val` properties are written when a row is inserted. Mutable `var` properties are tracked after create, update, and load operations, so `update` can write only changed fields.

```kotlin
class Patient(
    val mrn: String,
    var displayName: String,
    var active: Boolean,
) : Data()

object PatientsTable : DataTable<Patient>("patients") {
    val mrn = varchar("mrn", 64).uniqueIndex()
    val displayName = name("display_name")
    val active = active()
}

val patient = PatientsTable.create(
    Patient(mrn = "MRN-001", displayName = "Ada Lovelace", active = true)
)

patient.displayName = "Ada Byron"
PatientsTable.update(patient)
```

## Singleton tables

For application settings or other data that should have at most one row, extend
`SingletonDataTable` using an ordinary `Data` class:

```kotlin
class AppSettings(var maintenanceMode: Boolean) : Data() {
    companion object : SingletonDataTable<AppSettings>("app_settings") {
        val maintenanceMode = bool("maintenance_mode")
    }
}

val settings = AppSettings.getOrCreate { AppSettings(maintenanceMode = false) }
settings.maintenanceMode = true
AppSettings.update(settings)

val current = AppSettings.get()       // Throws if the table is empty.
val optional = AppSettings.find()     // Returns null if the table is empty.
```

The table retains normal Long IDs, CRUD operations, property mapping, and change tracking.
A PostgreSQL unique index enforces the one-row limit even for concurrent inserts, bulk inserts,
and direct SQL. `create` rejects a second row; `getOrCreate` safely returns the winning row when
multiple callers initialize concurrently under the default READ COMMITTED isolation. Its factory
should only construct data, since multiple callers can evaluate it. Deletion is allowed and makes
the table empty again.

Include the singleton table in your schema as usual. `SchemaUtils`, `AppSchema`, fresh-install
scripts, and dynamic migrations all install the limit. Converting an existing table requires
resolving any extra rows before applying the unique index; migrations do not choose a row to keep.

## Customizing inherited reads

To customize inherited reads, override `createQuery()` in a tuple, table, view, or an intermediate
base class. For example, this table's lookup methods only return active patients:

```kotlin
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere

// Inside PatientsTable:
override fun createQuery(): Query = super.createQuery().andWhere { active eq true }
```

All inherited lookups (`getById`, `findById`, `findByIds`, both `findByUnique` overloads,
`findAll`, `findWhere`, and `findPaged`), counts, and existence checks use this query. This works
through both the Long and UUID hierarchies, including `DataView` and `UuidDataView`. Additional
predicates are combined with the query's existing filter using `AND`. Overrides can also return
joined queries; select the tuple's `id` and all columns required by its `map()` implementation.
ID lookups assume one result per ID.

Return a fresh `Query` on each call because the helpers add predicates, ordering, and pagination.
The hook runs inside a database transaction when a query is needed. Transaction-cached ID
lookups can reuse earlier results, so keep the query scope stable within a cached transaction,
or use `db(cache = false)` at the outermost level. Explicit Exposed queries such as `selectAll()`
and write operations retain their existing behavior.

## Property-based columns

Columns can also be configured directly from Kotlin property references. `column` derives
the PostgreSQL column name (`displayName` becomes `display_name`), chooses the column type, and
preserves the property's nullability. It works on any Exposed `Table`, not only `DataTable`.
The returned column retains the reference as `column.dataProperty`. In both `DataTable` and
`UuidDataTable`, reads, writes, and change tracking use that reference, so the table property
can have a different name: `val nameColumn = column(Patient::displayName)` maps to `displayName`.
Mapping validation runs on the first read or write, after table initialization; call
`validateMapping()` explicitly to check a completed table earlier.

Each `TypedDataTuple` (including `DataTable` and `UuidDataTable`) owns its property mappings.
The read-only `columnProperties` map exposes explicit property references by SQL column name;
`columnGroupProperties` exposes them by group instance. To look up columns or groups by Data
property name, use `columnPropertyMap` or `columnGroupPropertyMap`. These resolved maps include
legacy name-based declarations and validate the completed table on first access. All four maps
are unmodifiable.

```kotlin
class Patient(
    val mrn: String,
    var displayName: String,
    var notes: String?,
    var active: Boolean,
) : Data()

object PatientsTable : DataTable<Patient>("patients") {
    val mrn = column(Patient::mrn, maxSize = 64).uniqueIndex()
    val displayName = column(
        Patient::displayName,
        maxSize = 255,
        caseInsensitive = true,
    )
    val notes = column(Patient::notes)
    val active = column(Patient::active)
}
```

Strings use `TEXT` by default, or `VARCHAR` when `maxSize` is provided. Supported options include
an explicit `name`, case-insensitive `CITEXT`, encrypted storage for supported types, timestamp
semantics for `Long`, decimal precision and scale, and foreign-key references. Overloads also cover
numeric types, booleans, UUIDs, binary values, JFK JSON values, `DataEnum` values, string/enum
lists, sets, and `ToJsonObject` values. Standard Exposed modifiers such as `default`, `index`,
`uniqueIndex`, and `check` can still be chained onto the resulting column.

## Text normalization

Text columns can normalize input before storage using `org.tekfive.keep.text.normalizeText`:

```kotlin
import org.tekfive.keep.text.TextCase
import org.tekfive.keep.text.normalizeText

val username = column(User::username)
    .normalizeText(trim = true, letterCase = TextCase.LOWER)
    .uniqueIndex()
val countryCode = varchar("country_code", 2)
    .normalizeText(trim = true, letterCase = TextCase.UPPER)
val displayName = column(User::displayName).normalizeText(trim = true)
val optionalCode = varchar("optional_code", 20).nullable()
    .normalizeText(trim = true, letterCase = TextCase.UPPER)
```

Both options are opt-in: `trim` defaults to false and `letterCase` to `TextCase.UNCHANGED`.
Trimming removes surrounding Kotlin whitespace, preserves internal whitespace, and runs before
locale-independent Unicode case conversion. Null stays null; whitespace-only input trims to an
empty string. Nullable columns work whether `nullable()` comes before or after normalization.
Length validation uses the normalized value, including Unicode case expansions.

Normalization applies to KEEP saves, ordinary Exposed inserts/updates and batch writes, and
bound parameters that use the column's type, including `eq`, `neq`, and `inList`. For example,
`username eq " ALICE "` binds `"alice"`. Length validation also applies to those query parameters.
`like` and KEEP's `ilike` patterns keep their existing behavior and are not trimmed or case-converted.
Raw SQL, SQL expression assignments/comparisons, and database-generated values bypass normalization.
Declare normalization before indexes and defaults; client-generated defaults pass through it.

Storage remains `TEXT`, `VARCHAR`, or `CITEXT`, so existing indexes and property mappings remain
usable and adding normalization alone produces no schema migration. `caseInsensitive = true`
controls comparison behavior independently of normalization. Encrypted text normalizes plaintext
before encryption; randomized encrypted columns still do not support equality searches.

Reads return exactly what is stored. Saving does not change the supplied data object's string
properties; a fresh load reflects the normalized stored value. Existing rows are not rewritten:
backfill them explicitly if needed, checking for collisions in unique columns. This feature
applies to scalar strings, not elements of string arrays or JSON values.

For `List<String>` or `List<String>?`, `column(Model::tags, maxSize = 100)` uses
`VARCHAR(100)[]`, limiting each string to 100 characters. Omitting `maxSize` uses `TEXT[]`.
The limit must be positive and cannot be combined with `encrypted = true`.

For `List<java.util.UUID>` or `List<UUID>?`, use `column(Model::relatedIds)` to store values as
PostgreSQL `UUID[]`. An explicit `name` overrides the derived snake-case column name. List order
and duplicates are preserved, and non-nullable lists support empty arrays.

For `List<Int>` or `List<Int>?`, use `column(Model::scores)` to store PostgreSQL `INTEGER[]`
values. An explicit `name` overrides the derived column name. Order, duplicates, negative values,
and the full Kotlin `Int` range are preserved. Non-nullable lists support empty arrays; KEEP's
data-table saves follow the existing convention of storing empty nullable collections as SQL NULL.

For a `List<JsonObject>` property, `val items = column(Model::items)` stores the list as a
JSONB array and reads it back as `List<JsonObject>`. Nullable lists are also supported;
no `FromJsonObject` converter is needed.

JSONB predicate trees use `JsonPathOperator`, `QueryNode`, and `LogicalOperator` from
`org.tekfive.keep.json.query`. They generate PostgreSQL JSONB predicates and bind path keys and
values through JDBC. Applications using the former `org.tekfive.keep.job.db` imports must update
those imports and rebuild.

Composite properties can use an existing `ColumnGroup` with
`val address = column(LocationData::address, AddressColumnGroup(this))`. The overload returns
the concrete group, so individual columns remain accessible (for example, `address.city`).
The group defines its columns' names and nullability and must create them on the receiving table.
The supplied property reference determines the mapping, so the table group's Kotlin name can differ.
For an `Address?` property, use `column(LocationData::address, OptionalAddressColumnGroup(this))`.
Writing `null` clears all four address columns; reading an all-null row returns an empty `Address`.

`Address` implements JFK `ToJsonObject`, and its companion implements `FromJsonObject<Address>`.
Use `address.toJsonObject()` and `Address.fromJson(json)`, or store an address as JSONB with
`column(LocationData::address, Address)`. Serialization writes `street`, `city`, `state` (the state
abbreviation), and `zip`. Parsing also accepts numeric state IDs and the legacy `zipCode` field.
Existing `toJson()`, `toJsonString()`, and `Address(json)` calls remain supported.

Scalar `DataEnum` properties use an `_id` suffix by default (`status` becomes `status_id`); an
explicit `name` is used unchanged.

Long and UUID foreign keys have the same property-based naming support through
`fkey(Order::customerId, CustomersTable)`. Nullable `Long?` and `UUID?` properties produce nullable
foreign-key columns, while standard KEEP foreign-key naming, indexing, and cascade behavior are
preserved.
Timestamp columns can likewise be declared as `timestamp(AuditRecord::createdAt)` or from a nullable
`Long?` property; the name is derived and KEEP's positive timestamp constraint is retained.

`java.time.LocalDate` and `LocalDate?` properties use `column(Model::birthDate)` and are stored as
PostgreSQL `DATE` values, without a time or time zone. Names default to snake case; use
`column(Model::birthDate, name = "date_of_birth")` to override the SQL column name.

`Instant` properties use `column` directly and are stored as epoch-millisecond `BIGINT` values by
default. Native PostgreSQL timestamp storage is an explicit per-column choice:

```kotlin
class AuditRecord(
    val occurredAt: Instant,
    val archivedAt: Instant?,
) : UuidData()

object AuditRecords : UuidDataTable<AuditRecord>("audit_records") {
    val occurredAt = column(AuditRecord::occurredAt)
    val archivedAt = column(
        AuditRecord::archivedAt,
        storage = InstantStorage.TIMESTAMP_WITH_TIME_ZONE,
    )
}
```

Both declarations expose `Column<Instant>` (or `Column<Instant?>`). `BIGINT` storage has millisecond
precision; `TIMESTAMP WITH TIME ZONE` uses PostgreSQL's native temporal representation.
To declare an Instant column by name, use `instant("occurred_at")`. It defaults to epoch
milliseconds in `BIGINT`, just like `column(Model::occurredAt)`. Use
`instant("occurred_at", storage = InstantStorage.TIMESTAMP_WITH_TIME_ZONE)` for native timestamp
storage, and chain `.nullable()` for an optional column.

Common operations include `create`, `save`, `update`, `delete`, `getById`, `findById`, `findByIds`, and `findByUnique`. `Data` instances also expose dirty-property information and JSON serialization helpers.

Import `org.tekfive.keep.data.copy` to shallow-copy a `Data` or `UuidData` instance while
preserving its concrete type, persisted ID, and dirty-property baseline:

```kotlin
val edited = patient.copy()
edited.displayName = "Ada Byron"
PatientsTable.save(edited)
```

Saving a linked copy updates the same database row. Its property assignments and change-tracking
state are independent of the source; referenced objects and collections are shared. Unsaved
copies remain unsaved, and UUID copies receive their own temporary IDs. Constructor values must
be available as properties, including inherited constructor properties; other instance fields
are reinitialized by the constructor.

## Transaction timestamps

Use the top-level `dbTransactionInstant()` function to get the current transaction's timestamp:

```kotlin
import org.tekfive.keep.db.dbTransactionInstant

val timestamp = dbTransactionInstant()
```

Within a KEEP `db` block or Exposed JDBC transaction, this reads PostgreSQL's transaction-start
timestamp, which remains stable throughout that database transaction. Outside a transaction it
returns `Instant.now()`. Each transactional call queries the server so commits and rollbacks
cannot leave a stale cached timestamp.

## Companion tables

`Data.table` and `UuidData.table` dynamically resolve the concrete class's companion table:

```kotlin
class Contact(var displayName: String) : Data() {
    companion object : DataTable<Contact>("contacts") {
        val nameColumn = column(Contact::displayName)
    }
}

val contact = Contact("Ada")
check(contact.table === Contact)
val columnsByProperty = contact.table.columnPropertyMap
```

For UUID data, use `UuidData` and a companion extending `UuidDataTable<Contact>`.
Accessing `table` throws `IllegalStateException` if the concrete class has no companion table,
or if its companion uses the wrong table type or maps a different data class. Separate table
objects remain supported; their data instances simply cannot use this property.

Unsaved `UuidData` objects return a stable temporary UUID from `id`. Like a temporary negative ID,
it identifies the instance without marking it as persisted: `idOrNull` remains null, `linkedToDb`
remains false, and `save()` inserts a new row. Saving replaces the exposed ID with the persisted
UUIDv7 (or an explicitly supplied ID). Unlinking or deleting the object restores its temporary ID.
Temporary IDs are not automatically persisted or included in default JSON and do not affect
equality or dirty tracking. Long-ID `Data` objects continue to require saving before accessing `id`.

## UUID primary keys

KEEP also supports UUIDv7 primary keys as an additive alternative to the existing shared-sequence `Long` strategy. Extend `UuidData` and `UuidDataTable`; the table creates a native PostgreSQL `UUID` column and generates UUIDv7 values in the client, so PostgreSQL 16 and later are supported without an extension or server-side UUID function.

```kotlin
class Patient(
    val mrn: String,
    var displayName: String,
) : UuidData()

object PatientsTable : UuidDataTable<Patient>("patients") {
    val mrn = varchar("mrn", 64).uniqueIndex()
    val displayName = name("display_name")
}

val patient = PatientsTable.create(Patient("MRN-001", "Ada Lovelace"))
check(patient.id.version() == 7)
```

UUID counterparts are available for views, join tables, database tuple caches, unique-name checks, and foreign keys. `TypedDataJoinTable` can be used when an association spans different identity types. The generated UUID default is an Exposed client default, so inserts performed outside Exposed must supply an `id` explicitly.

## Next steps

Read [database connections and transactions](database.md), [caching](caching.md), and [auditing](auditing.md). The [data package reference](../src/main/kotlin/org/tekfive/keep/data/_package.md) describes mapping and change tracking internals.
