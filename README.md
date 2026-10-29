# KEEP

KEEP stands for **K**otlin **E**xposed **E**nhancements for **P**ostgreSQL.

A Kotlin persistence utility library built on JetBrains Exposed. KEEP provides table/data mapping helpers, PostgreSQL-specific column and query extensions, migration tracking, transaction-scoped tuple caching, database-backed locks, encrypted column types, paging helpers, and a lightweight database-backed job runner.

## Usage documentation

Start with [Getting started](docs/getting-started.md) for installation, connection setup,
and a complete create/read/update example. Browse the [guide index](docs/README.md)
for a suggested reading order.

| Guide | Covers |
| --- | --- |
| [Records and tables](docs/tables.md) | Long and UUID IDs, property mapping, singleton tables, relationships, and CRUD |
| [Connections and transactions](docs/database.md) | Configuration, pooling, transaction scopes, and error classification |
| [Caching](docs/caching.md) | Entire-table caches, refresh, expiration, and transaction boundaries |
| [PostgreSQL columns and queries](docs/postgresql.md) | JSON, arrays, text search, and column helpers |
| [Schemas and database policies](docs/schema.md) | Fresh-install SQL, types, indexes, constraints, and triggers |
| [Migrations](docs/migrations.md) | Plan and apply schema changes; run recorded migrations |
| [Locks and leases](docs/locks.md) | Transaction locks, named locks, lease renewal, and fencing |
| [Counters and rate limits](docs/counters.md) | Shared counters, expiry windows, and HTTP rate limits |
| [Encryption](docs/encryption.md) | Encrypted columns, keyset loading, and recovery modes |
| [Auditing](docs/auditing.md) | Writer attribution, changed properties, and encrypted history |
| [Background jobs](docs/jobs.md) | Setup, scheduling, retries, reporting, cancellation, and upgrades |
| [Paging](docs/paging.md) | Search, filters, sorting, and paginated responses |
| [Development and releases](docs/development.md) | Tests, local dependencies, builds, and publishing workflows |

## Design Priorities

### Exposed First

[![JetBrains logo](https://resources.jetbrains.com/storage/products/company/brand/logos/jetbrains.svg)](https://www.jetbrains.com/exposed/)

KEEP extends Exposed instead of replacing it. Tables remain ordinary Exposed table objects, queries still use Exposed DSL expressions, and transactions still run through Exposed's transaction manager. KEEP adds conventions for common application patterns such as ID-backed data classes, standard foreign key names, JSON columns, active flags, timestamps, and paged query responses.

### PostgreSQL-Oriented

[![PostgreSQL logo](https://www.postgresql.org/media/img/about/press/elephant.png)](https://www.postgresql.org/)

The library assumes PostgreSQL for its richer database features. It includes helpers for arrays, JSONB paths, CITEXT, PostGIS coordinate values, row locking, and SQLSTATE-aware retry behavior. The test suite uses PostgreSQL through Testcontainers so integration behavior is checked against a real database.

### Operational Defaults

KEEP includes small operational building blocks that application code usually needs around persistence: idempotent migration execution, transaction cache scoping, advisory-style lock rows, encrypted database values, and persisted background jobs with retries, scheduling, timeout detection, and job logs.

## Requirements

[![Kotlin logo](https://github.com/Kotlin.png?size=64)](https://kotlinlang.org/)

[![Docker logo](https://github.com/docker.png?size=64)](https://www.docker.com/)

- Java 21+
- Kotlin 2.x
- PostgreSQL for runtime use
- Docker or a compatible Testcontainers environment for integration tests

## Installation

[![JitPack logo](https://github.com/jitpack.png?size=64)](https://jitpack.io/)

Add JitPack to your dependency repositories:

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}
```

Then add KEEP:

```kotlin
implementation("com.github.TekFive:keep:v1.1.1")
```

KEEP resolves its ACK, JFK, and KViash dependencies from JitPack. When building KEEP
from source, its build checks the local Maven repository first, allowing a locally
published artifact with the same coordinates to override a remote artifact.

## Development

See [Development and releases](docs/development.md) for tests, local setup, and publishing.
