# Audit

This package records who changed a record, which values they changed, and when.
Each recorded change also keeps a copy of the stored record. History stays
available after a record is removed. Applications supply their own writer
identities and business rules.

## Code

| Type | Responsibility |
| --- | --- |
| `AuditedUuidTable` | Requires a writer for record changes, validates property declarations, and writes provenance and snapshots. |
| `AuditStateBinding`, `PropertyProvenance` | Connect creation attribution and the last writer of each property to application records. |
| `AuditWriterCodec` | Maps an application's writer type to database columns and JSON. |
| `AuditValueContributor` | Tracks individual values stored in a single mapped property and merges them against the current row. |
| `AuditedChildren`, `AuditedParent` | File child changes in one root record's history. |
| `AuditSnapshot`, `AuditSnapshotTable` | Store encrypted record copies and query history, previous values, and collection membership changes. |

## Database tables

| Table | Contents |
| --- | --- |
| `<root table>_snapshots` | Writer, transaction time, changed property IDs, encrypted record JSON, and optional child identity. |

## Architecture

### Application declarations

An application table extends `AuditedUuidTable<Record, Writer>`. Its record
extends `UuidData`. `AuditStateBinding` identifies the mutable creation-writer
and provenance properties, and the application maps those properties to columns.
`auditProvenances` provides JSON storage for `List<PropertyProvenance<Writer>>`.
The writer codec supplies a column group and explicit JSON encoding and decoding.

Every other mutable property needs an explicit declaration. A column or bound
column group uses `.audited("canonical-uuid")` for an ordinary value,
`.secret("canonical-uuid")` for an encrypted value whose contents must stay out
of history, or `.unaudited()` for bookkeeping. An excluded derived property uses
`.unaudited(excludeFromSnapshot = true)`. Audit IDs must be unique within a table
and must not be reused when a property is renamed or removed.

Call `checkPropertyMapping()` after constructing a table to validate its complete
mapping. Writes also perform this check. Validation closes the audit declarations.
An encrypted property must be secret, including an encrypted immutable property
that would otherwise enter the snapshot. Secret declarations currently require a
mutable property, so immutable encrypted values need a different record design.

### Writes and transactions

Use `save(record, writer)`, `create(record, id, writer)`, `remove(record, writer)`,
or `removeAll(ids, writer)`. `edit(id, writer) { record -> ... }` locks a stored
row before applying a read/modify/write change. Ordinary inherited CRUD methods,
including `deleteByColumnValue`, reject writes because they have no writer.

An update locks the stored row. It preserves creation attribution and merges
provenance into the current stored provenance. The underlying KEEP update writes
the caller's dirty properties. A fresh database read supplies the snapshot,
which therefore includes other writers' changes to untouched properties.
An unchanged record produces no history. An update containing only excluded
bookkeeping changes can write the row without producing a history entry.

The row change and history insert share a transaction and a savepoint. Failure
rolls back the audited operation even if the caller catches the exception. Earlier
work in the transaction remains intact when savepoint rollback succeeds. Commit
attempts through KEEP's supported transaction APIs are refused inside audit hooks.
Hooks must not issue transaction-control SQL, use independent connections, or roll
back the enclosing transaction. Such operations are outside the audit contract.

Audited operations clear the transaction cache before and after their work so
cached rows and cached misses cannot survive a write or a failed operation.
Discard or reload record instances after any transaction rollback. Their in-memory
identity, values, and dirty baseline are not restored by database rollback.

`beforeSave` validates or prepares application values inside the transaction.
`mutateAudited` is a protected extension for operations such as reference moves.
It locks selected rows, runs the supplied mutation, and records each resulting
row. Its callback must restrict writes to the selected rows of that table and
must not delete them. Provenance is preserved unless explicitly requested.

### Snapshot contents

Initialize KEEP's database encryption provider before constructing tables with
encrypted columns. Snapshot JSON uses encrypted storage. Secret fields are also
removed from the serialized payload, so history readers cannot recover them.
Provenance is omitted from the payload because the snapshot stores its own writer
and changed property IDs.

`serializeSnapshot` honors an application override of `toJsonObject()`. For a
record using KEEP's inherited serializer, it follows the record's JSON property
selection and additional values, encodes UUIDs and Java times as strings, and uses
the writer codec for creation attribution. This supports KEEP's pinned JSON library
without changing application serialization or dependency versions. Applications
can override `serializeSnapshot` for other representations. The serializer must retain
mapped property names for secret and excluded values and must not copy those
values under other keys. KEEP removes declared fields after making a detached
copy of the serialized JSON.

### Values stored together

Register a column with `.contributedBy(contributor)` when it holds several
independently audited values. The contributor supplies IDs for initial values
and changes and retains an independent baseline when loaded or saved. Its `merge`
method replaces the contributed value on `data`, starting from `stored` and applying
only the caller's changed IDs. It must use replacement
values when changing mutable containers so KEEP's dirty tracking sees the change.
Contributed IDs must not conflict with fixed property IDs or child collection IDs.

### History ownership and schema registration

Declare a root collection with `children(name, id)` and give a child table an
`AuditedParent` containing that collection and its parent-ID property. Child
snapshots use the parent's data ID plus the child collection and row IDs. Child
tables share the root's history table. A child of a child is rejected.

Use `withAssociatedTables` from the data package when building `KeepSchema.tables`
or `AppSchema.tables`. A root audit table contributes its snapshot table. An
application can extend `associatedTables` for its own related tables. The snapshot
ID uses KEEP's usual global sequence, which `AppSchema` declares by default.

History reads require a transaction. History and paging use the snapshot sequence,
since a transaction that starts earlier can acquire a row lock and write later.
Timestamps retain the transaction start time. `previousSnapshot` stays within the
same root or child record. The generic
`findMembershipWriters` query can identify who last added members to a pin set or
another collection when the application supplies its JSON member extraction.

## Limits

These APIs record application writes made through the audited table. Direct
Exposed or JDBC writes and database cascades do not generate history. History
storage is not a tamper-proof ledger, and this package does not provide read
authorization, retention rules, user lookup, or writer priority policies.
