# Database connections and transactions

This package opens database connections and runs work in transactions. It also
provides temporary query caches and a guard for work that must not commit.

## Code

| Type or function | Responsibility |
| --- | --- |
| `DbConnection` | Configures the PostgreSQL driver, optional connection pool, and Exposed database |
| `db` | Runs a transaction or joins the current one, with optional query caching |
| `TransactionCache` | Keeps query results for the current thread's outer cached transaction |
| `CommitGuardConnection`, `noDbCommit` | Refuse commits through KEEP and Exposed while a guarded block runs |
| `PostgresMetadata` | Loads foreign key metadata once per schema for Exposed's schema comparison |

## Metadata

`DbConnection.startup` registers `PostgresMetadata` as Exposed's PostgreSQL
metadata provider before connecting. The registration also applies to subsequent
Exposed PostgreSQL databases in the process.

The provider requests all foreign keys in each mapped schema through pgjdbc,
then retains the constraints whose tables and columns are mapped. Composite
keys retain their column order and update and delete actions. Tables with no
foreign keys also receive a cache entry.

Exposed owns the metadata cache. Its normal cache reset clears these entries,
including the reset before each migration plan. Schema comparison therefore
reads the database again on each run.

## Transactions

Nested `db` calls normally join the current transaction. `nestTransactions=true`
requests a nested transaction. An enabled `TransactionCache` lasts for the
outer cached block and is removed when that block returns or throws.

`noDbCommit` guards the current connection and related outer transactions until
the block ends. Rollback remains available. Raw transaction-control SQL and
direct driver connections bypass the guard.
