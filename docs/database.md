# Database connections and transactions

[Usage guide index](README.md) · [KEEP README](../README.md)

[![PostgreSQL logo](https://www.postgresql.org/media/img/about/press/elephant.png)](https://www.postgresql.org/)

[![JetBrains logo](https://resources.jetbrains.com/storage/products/company/brand/logos/jetbrains.svg)](https://www.jetbrains.com/exposed/)

On this page:

- [Database Connections](#database-connections)
- [Transaction Utilities](#transaction-utilities)
- [Next steps](#next-steps)

## Database Connections

`DbConnection.startup()` reads connection settings through ACK. Pooling is disabled
by default; enable it with `POOL_JDBC_CONNECTIONS=true`. The pool defaults to
25 connections (`JDBC_CONNECTION_MAX`) and a 30-second wait for an available
connection (`JDBC_CONNECTION_TIMEOUT_SECONDS`). Register configuration sources
before startup. Restart the connection provider to apply changes.

Catch-site classifiers are available on any `Throwable`, including wrapped Exposed/JDBC errors:

```kotlin
import org.tekfive.keep.utils.isDatabaseException
import org.tekfive.keep.utils.isRecoverableDatabaseException

val databaseRelated = exception.isDatabaseException()
val recoverable = exception.isRecoverableDatabaseException()
```

Recoverable failures include connection/pool failures, JDBC transient errors, I/O errors beneath
a database exception, PostgreSQL resource shortages, shutdowns, session/transaction timeouts,
serialization failures, deadlocks, and lock contention. Data, constraint, syntax, authentication,
invalid transaction state, and query-cancellation errors return `false`. Unknown errors and bare
I/O exceptions also return `false`. Causes and JDBC `nextException` chains are checked safely;
explicit non-recoverable errors take precedence, and suppressed cleanup errors are ignored.
The SQLSTATE classification follows the [PostgreSQL error codes](https://www.postgresql.org/docs/current/errcodes-appendix.html).

Recovery can require a fresh connection and retrying the entire transaction. A connection failure
may leave the commit outcome unknown, so `true` does not guarantee that replaying a write is safe.
These helpers do not change the job subsystem's existing, broader database retry policy.

## Transaction Utilities

The `db { ... }` helper centralizes Exposed transaction usage. If code is already running inside a transaction, `db` reuses it by default; otherwise it starts one. This lets table helpers and application code compose without accidentally opening unrelated transactions.

```kotlin
val updated = db {
    val patient = PatientsTable.getById(patientId)
    patient.active = false
    PatientsTable.save(patient)
}
```

Transaction-scoped tuple caching is enabled by default through `TransactionCache`. Repeated `getById` or `findById` calls for the same table and ID inside a transaction can return the cached object instead of issuing duplicate reads.

```kotlin
db {
    val first = PatientsTable.getById(patientId)
    val second = PatientsTable.getById(patientId)
    check(first === second)
}
```

For lower-level work, KEEP also exposes helpers such as `dbConnection()`, `dbCommit()`, `rollback()`, `inDbTransaction()`, and `dbTransactionAt()`.

Use `noDbCommit` inside an existing transaction when a section of work must not commit:

```kotlin
db {
    noDbCommit {
        // Database work; commit attempts throw IllegalStateException.
    }
    // The transaction can commit normally after the scope ends.
}
```

Scopes can nest and restore normal behavior even when the block throws. The block's result is
returned, and rollback remains available. The guard applies to the current connection, leaving
independent connections unaffected. It rejects `dbCommit()` (including `dbCommitQuietly()`),
Exposed commits on the guarded transaction, and JDBC `commit()` or enabling `autoCommit` through
`dbConnection()` and connections opened by `DbConnection`. Savepoint-only nested transaction
completion does not commit the connection. Raw transaction-control SQL and direct driver
connections (including unwrapped handles) bypass the guard and must not be used inside the scope.

## Next steps

Use [getting started](getting-started.md) for a complete connection example, or the [database package reference](../src/main/kotlin/org/tekfive/keep/db/_package.md) for transaction and metadata behavior.
