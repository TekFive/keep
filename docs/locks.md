# Locks and leases

[Usage guide index](README.md) · [KEEP README](../README.md)

[![PostgreSQL logo](https://www.postgresql.org/media/img/about/press/elephant.png)](https://www.postgresql.org/)

On this page:

- [Database Locks](#database-locks)
- [Lease Locks with Check-ins](#lease-locks-with-check-ins)
- [Next steps](#next-steps)

## Database Locks

`org.tekfive.keep.lock.TransactionLocks` provides named PostgreSQL transaction advisory locks.
Call it inside an existing `db { }` or Exposed transaction:

```kotlin
db {
    TransactionLocks.acquire("audit-chain:$tenantId") // Wait until available.
    appendAuditEntry()
}

db {
    if (!TransactionLocks.tryAcquire("audit-chain:$tenantId")) return@db
    appendAuditEntry()
}
```

`tryAcquire` returns immediately when the lock is held elsewhere; `acquire` waits subject to
PostgreSQL's configured lock and statement timeouts. Locks are reentrant within the owning
transaction and released automatically on commit or rollback. No table or explicit unlock is
needed. Keys use `hashtextextended(key, 0)`; use prefixes to separate application resources.

`LocksTable` provides database-backed named locks for protecting scheduled or singleton work across processes. It creates a lock row for each lock name and uses PostgreSQL row locking to make the protected block exclusive.

```kotlin
val ran = LocksTable.tryRunWithLock(
    lockId = "daily-patient-sync",
    minSecondsSinceLastLock = 60 * 60,
    maxSecondsToWaitOnLock = 10,
) {
    runPatientSync()
}

if (!ran) {
    // Another process is running it, the lock timed out, or the minimum interval has not elapsed.
}
```

This is useful for scheduled tasks, periodic maintenance, cache refreshes, or external integrations that should not be executed concurrently by multiple application nodes.

## Lease Locks with Check-ins

`org.tekfive.keep.lock.LeaseLockTable` holds ownership in a database row with an expiry, so work
can span transactions without reserving a connection. Define a table and add it to your
`KeepSchema.tables`; fresh installation and dynamic migration handle it as an ordinary table.
Use a schema-qualified table name when it lives outside the connection's default search path.

```kotlin
import org.tekfive.keep.lock.LeaseLockTable
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object WorkLocks : LeaseLockTable("work_locks")

fun syncCustomer(customerId: Long) {
    val lease = WorkLocks.tryAcquire(
        key = "customer-sync:$customerId",
        leaseFor = 60.seconds,
        maxLifetime = 30.minutes, // Optional hard deadline, including renewals.
    ) ?: return

    lease.use {
        for (batch in loadBatches(customerId)) {
            it.checkIn()
            processBatch(batch, it.fencingToken)
        }
    }
}
```

`tryAcquire` makes one attempt and returns null for an active owner or a database row-lock
timeout. Other database failures propagate. `acquire(key, leaseFor, waitTimeout, maxLifetime)`
retries contention with jitter and throws `LockAcquireTimeoutException` when its retry budget
expires. It returns its connection between attempts and supports thread interruption. Keys are
case-sensitive, up to 512 characters; acquisitions are not reentrant.

Each acquisition has a new UUID `ownerToken` and an increasing per-key `fencingToken`. A
`LockLease` exposes these plus `acquiredAt`, the last confirmed `expiresAt`, and an optional
`deadlineAt`. `checkIn()` renews from current database time, capped by the hard deadline, and
returns the committed expiry. It throws `LeaseLostException` after expiry, release, or takeover;
an expired handle cannot resurrect itself. Check in well before expiry, for example every 20
seconds with a 60-second lease, allowing for the duration of each batch and database delays.
Renewal is explicit: there is no background heartbeat that could keep stalled work alive.

`release()` clears only that acquisition and returns false if it was already released or
replaced. `close()` calls release, making `use` suitable for cleanup on success or failure.
Database errors propagate from both; a failed check-in does not establish continued ownership.
Expiry makes the key available to another acquirer without a sweeper or explicit release.
Released and expired rows are retained to preserve fencing generations; do not delete them or
modify them through application transactions.

All lease operations commit on a separate connection and close it before returning. An enclosing
`db` rollback does not undo acquisition, renewal, or release; `noDbCommit` still protects the
caller's connection. The default provider is `DbConnection.createConnection`, so initialize
`DbConnection` first. Alternatively, pass `connectionProvider = { ... }` returning a fresh,
exclusively owned JDBC connection for each operation. Connections must use the same database and
schema. The provider's connection/login and socket timeouts remain its responsibility; ensure
the pool has capacity for lease operations when application transactions are open.

`operationTimeout` defaults to 5 seconds and bounds each SQL statement, with a shorter row-lock
timeout. The waiting acquisition uses a monotonic retry budget; connection establishment and
in-flight database operations can extend its observed elapsed time. Timestamps are stored as
epoch milliseconds and calculated using PostgreSQL `clock_timestamp()` after obtaining the row
lock. Row locks are held only for the brief lease operation, never for the protected work.

**Fencing is required for protection against stale workers.** Expiry permits takeover but cannot
stop a paused worker from resuming. The protected resource must atomically enforce the increasing
`fencingToken` and reject older generations; passing the token without enforcing it is insufficient.
A check-in before a write is not atomic with that write. The lease does not provide exactly-once
execution, and an external service without fencing needs its own idempotency or concurrency controls.

## Next steps

See [transactions](database.md) for transaction boundaries and [jobs](jobs.md) for built-in job concurrency limits.
