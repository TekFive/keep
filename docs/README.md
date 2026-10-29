# KEEP usage guides

[KEEP README](../README.md)

These guides describe the APIs in this checkout. Start with [Getting started](getting-started.md),
then follow the topics you need. Longer examples use application-defined names and functions;
replace those with your own models and business operations.

## First application

1. [Install KEEP and connect to a database](getting-started.md).
2. [Define records, tables, and singleton settings](tables.md).
3. [Choose transaction boundaries and connection settings](database.md).
4. [Declare your schema](schema.md) and [plan migrations](migrations.md).

## Query and persistence features

| Guide | Use it when you need to… |
| --- | --- |
| [PostgreSQL columns and queries](postgresql.md) | Store JSON, search text, and query arrays |
| [Paging](paging.md) | Return searchable and sortable API lists |
| [Caching](caching.md) | Cache small reference tables or repeated transaction reads |
| [Encryption](encryption.md) | Encrypt stored values and load keysets |
| [Auditing](auditing.md) | Attribute changes and retain encrypted record snapshots |

## Background and shared work

| Guide | Use it when you need to… |
| --- | --- |
| [Background jobs](jobs.md) | Schedule work, retry failures, report progress, or request cancellation |
| [Locks and leases](locks.md) | Coordinate exclusive work across application instances |
| [Counters and rate limits](counters.md) | Maintain shared usage totals or enforce request budgets |
| [Development and releases](development.md) | Run KEEP's tests or publish a release |

Provider sections embed logo image URLs directly in Markdown. The logos link to the
provider's own website or project, and the image URLs use provider-hosted assets or
that provider's public project avatar.
