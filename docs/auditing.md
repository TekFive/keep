# Auditing record changes

[Usage guide index](README.md) · [KEEP README](../README.md)

[![PostgreSQL logo](https://www.postgresql.org/media/img/about/press/elephant.png)](https://www.postgresql.org/)

[![Tink logo](https://github.com/tink-crypto.png?size=64)](https://developers.google.com/tink)

`AuditedUuidTable<Record, Writer>` adds writer-required saves, property provenance,
and encrypted historical snapshots to `UuidDataTable`. Applications provide a writer
codec and bind their creation-writer and provenance properties through
`AuditStateBinding`. Mutable properties declare stable IDs with `.audited(id)`;
encrypted secrets use `.secret(id)` and stay out of snapshots.

Updates lock the stored row and preserve other writers' changes to untouched fields.
The row write and snapshot share a savepoint, including when an application catches
a failed operation. Parent/child history and independently audited values inside a
JSON column use explicit declarations and contributors. Applications retain their
authorization, archiving, writer-priority, and other business rules.

Use `withAssociatedTables` when assembling schema tables to include each root's
snapshot table. See the [audit package guide](../src/main/kotlin/org/tekfive/keep/audit/_package.md)
for declaration, serialization, transaction, and history contracts.

## Using an audited table

1. Define a record extending `UuidData`, with creation-writer and property-provenance fields.
2. Supply an `AuditWriterCodec` for your application's user or system identity.
3. Extend `AuditedUuidTable<Record, Writer>` and bind the attribution properties with `AuditStateBinding`.
4. Mark each mutable property as audited, secret, or unaudited; initialize encryption before constructing encrypted columns.
5. Add the root table and its supporting snapshot table through `withAssociatedTables` in your schema.
6. Use writer-aware operations for every change:

```kotlin
// accounts is your configured AuditedUuidTable; writer is your application identity.
db {
    accounts.save(account, writer)
    accounts.edit(account.id, writer) { current ->
        current.displayName = "Updated name"
    }
    accounts.remove(account, writer)
}
```

The calls above illustrate the API; define `accounts`, `account`, and `writer` in
application code. Ordinary inherited writes without a writer are rejected. Direct
SQL and database cascades do not create audit snapshots.

Read the [audit package guide](../src/main/kotlin/org/tekfive/keep/audit/_package.md)
for codec declarations, property IDs, transaction behavior, children, and history queries.

## Next steps

Start with [UUID tables](tables.md), then configure [encrypted storage](encryption.md).
