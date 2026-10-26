# Data

This package maps application records to database rows. It keeps record identity,
detects changed properties, and provides table queries and writes. Applications
can declare their table beside a record as its companion object.

## Code

| Types | Responsibility |
| --- | --- |
| `IdentifiedData`, `Data`, `UuidData` | Hold record identity and the baseline used to detect changed values. |
| `TypedDataTuple`, `DataTuple`, `UuidDataTuple` | Map constructor properties and query rows. |
| `DataTable`, `UuidDataTable` | Insert, selectively update, and delete records with long or UUID IDs. |
| `SingletonDataTable` | Enforce at most one row and provide singleton lookup and concurrent initialization. |
| `ColumnGroup` and property column helpers | Bind one property to a column or a group of columns. |
| `DataView`, `UuidDataView` | Map read-only database views. |
| Table and tuple caches | Cache records with the configured lifetime and lookup behavior. |
| `DataChangeTable` | Declare storage columns for application-managed change records. |
| `HasAssociatedTables`, `withAssociatedTables` | Include supporting tables in a schema declaration. |

## Architecture

### Mapping and change tracking

Table declarations can bind columns directly to Kotlin properties. Mapping checks
that constructor properties and table declarations agree. Column groups map a
single property to several database columns.

Loading or writing a record captures its mutable properties as a baseline.
Ordinary updates write changed properties. An explicit-column update writes only
the requested columns and updates the corresponding baseline. Replace mutable
collections when changing them; the baseline does not deep-copy their contents.

### Identity and queries

`DataTable` manages a long ID using the global sequence. `UuidDataTable` generates
UUIDv7 IDs and also accepts explicit creation IDs. Query subclasses can override
the query used by inherited lookups. Transaction caching assumes query scope stays
stable during that transaction.

### Audited tables

The sibling `audit` package adds writer-required persistence and historical
snapshots to UUID tables. `UuidDataTable.deleteByColumnValue` is overridable so an
audited subclass can refuse deletion without writer attribution. Ordinary tables
retain their existing deletion behavior. `DataChangeTable` is a storage declaration
and does not automatically observe table writes.

### Associated tables

A table implements `HasAssociatedTables` when it owns supporting tables that must
be installed with it. Pass the root tables to `withAssociatedTables` and use the
returned list as the application's schema tables. The helper expands declarations
in order, visits each table object once, and tolerates cycles. Distinct table
objects with the same SQL name are rejected. Using the expanded list for the schema
keeps creation, migration, and removal aware of the same tables.
