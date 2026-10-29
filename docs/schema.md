# Schemas, installation SQL, and database policies

[Usage guide index](README.md) · [KEEP README](../README.md)

[![PostgreSQL logo](https://www.postgresql.org/media/img/about/press/elephant.png)](https://www.postgresql.org/)

[![JetBrains logo](https://resources.jetbrains.com/storage/products/company/brand/logos/jetbrains.svg)](https://www.jetbrains.com/exposed/)

On this page:

- [Connection-Free Fresh Installation SQL](#connection-free-fresh-installation-sql)
- [Typed PostgreSQL Constraints and Trigger Policies](#typed-postgresql-constraints-and-trigger-policies)
- [Next steps](#next-steps)

## Connection-Free Fresh Installation SQL

`PostgresFreshInstallGenerator` renders a complete PostgreSQL installation script from a `KeepSchema` without opening a database connection. This is a fresh install, not a comparison or merge. It includes the schema, extensions, typed enums, sequences, tables, constraints, indexes, views, and materialized views in dependency order.

```kotlin
object ApplicationSchema : KeepSchema("app") {
    override val extensions = listOf(KeepSchema.CITEXT)
    override val tables = listOf(UsersTable, OrdersTable)
    override val sequenceDefinitions = listOf(
        PostgresSequenceDefinition(
            name = "order_number_seq",
            startWith = 1000,
            cache = 20,
        )
    )
    override val views = listOf(
        PostgresViewDefinition(
            name = "active_users",
            query = "SELECT id, email FROM app.users WHERE active",
        )
    )
}

PostgresFreshInstallGenerator.generate(
    keepSchema = ApplicationSchema,
    output = Path.of("install.sql"),
    overwrite = true,
    targetVersion = PostgresTargetVersion(major = 16),
)
```

`KeepSchema` provides extension-name constants: `BTREE_GIN`, `BTREE_GIST`, `CITEXT`, `HSTORE`, `LTREE`,
`PGCRYPTO`, `PG_STAT_STATEMENTS`, `PG_TRGM`, `POSTGIS`, `TABLEFUNC`, `UNACCENT`, and `UUID_OSSP`.
For example, use `listOf(KeepSchema.CITEXT, KeepSchema.PG_TRGM)`. Custom extension names remain strings.

`beforeTablesSql` and `afterTablesSql` remain fresh-install-only SQL escape hatches for objects outside the typed declarations; dynamic migration does not compare or replay them. Foreign-key targets must all be declared in the same `KeepSchema`, preventing an apparently complete script from silently depending on an undeclared table.

`DataTableSchema` replaces `DataTableSchemaHooks` and has one declaration property:
`postgresObjects: List<PostgresTableObject>`. `DataTable` and `UuidDataTable` implement it.
The former `customTypes`, `customIndices`, `postSchemaCreateSql`, and `postSchemaCreate` properties
are removed. Fresh installation, `AppSchema.create()`, and dynamic migrations share the same typed
object definitions, and order creation by dependencies rather than hook phases.

Declare shared types on `KeepSchema`:

```kotlin
override val types = listOf(
    PostgresEnumDefinition("pipeline_status", listOf("draft", "published", "archived")),
)
```

Types are created before tables. Dynamic migrations support creating enums and adding labels in
declaration order; adding labels to an existing enum requires `plan.executeAutocommit(database)`.
Removing or reordering existing labels requires an explicit data migration. Undeclared application
enums are dropped after dependent table changes in destructive mode and retained in non-destructive
mode. Extension-owned types are preserved. Missing types are temporarily created during planning
and rolled back before it returns, together with simulated extension installations and renames.

Use the table's `postgresObjects` DSL for indexes, unique/foreign-key/check/exclusion constraints,
and row triggers. Use a getter or lazy initializer when referring to other table objects:

```kotlin
override val postgresObjects by lazy {
    postgresObjects {
        foreignKeyConstraint(
            "pursuit_pipeline_assignments_current_stage_version_fk",
            pipelineVersionId to PipelineStages.pipelineVersionId,
            currentStageId to PipelineStages.id,
        )
        index("assignments_stage_idx", pipelineVersionId, currentStageId)
        index(
            "assignments_recent_idx",
            listOf(createdAt.indexKey(SortOrder.DESC)),
            include = listOf(currentStageId),
            predicate = SqlExpression("current_stage_id IS NOT NULL"),
        )
        checkConstraint("assignments_positive_version", SqlExpression("pipeline_version_id > 0"))
    }
}
```

Index declarations support uniqueness, access methods, included columns, sort order, expression
keys (`PostgresIndexKey.ExpressionKey`), predicates, and PostgreSQL 15+ `nullsNotDistinct`.
Expressions and predicates use unqualified column names inside explicit `SqlExpression` fragments;
complete DDL statements are not declarations. `exclusionConstraint` accepts a list of typed
`ExclusionElement` values (expression plus operator), an index method, and an optional predicate.
Dynamic comparison uses PostgreSQL-normalized definitions, so harmless formatting differences do
not rebuild indexes or constraints. Changed indexes and expression constraints are replaced;
removed declarations are dropped while preserving stored rows. Matching definitions emit no SQL.

Column pairs preserve the order of a composite foreign key. Both tables must be listed in
`KeepSchema.tables`; the referenced columns must form a PostgreSQL primary or unique key.
All tables and unique keys are created before these foreign keys, even when the referencing table
is listed first. The builder also accepts `onDelete` and `onUpdate` (`PostgresForeignKeyAction`),
`deferrable`, and `initiallyDeferred`.

Dynamic migrations compare foreign-key targets, ordered columns, referential actions, and deferral
settings. They add missing constraints, replace changed ones, validate matching `NOT VALID`
constraints, and remove foreign keys no longer declared on managed tables. Foreign keys declared
through Exposed are also retained. Constraint removal is permitted in non-destructive mode because
it preserves stored rows. Matching declarations emit no SQL.

The target PostgreSQL version is explicit and defaults to PostgreSQL 16, making dialect decisions deterministic. Generated scripts can be checked into source control or packaged with an application.

## Typed PostgreSQL Constraints and Trigger Policies

Use Exposed's `check` and filtered `uniqueIndex` APIs for typed checks and partial indexes. KEEP's
`postgresObjects` DSL adds real PostgreSQL `UNIQUE` constraints and row-trigger policies while
retaining the column types declared by the table:

```kotlin
object PipelineVersions : DataTable<PipelineVersion>("pipeline_versions") {
    val pipelineId = long("pipeline_id")
    val version = integer("version")
    val status = dataEnum<PipelineVersionStatus>("status_id")
    val name = varchar("name", 255)

    init {
        check("pipeline_versions_version_check") { version greater 0 }
        uniqueIndex("pipeline_versions_one_draft_ix", pipelineId) {
            status eq PipelineVersionStatus.DRAFT
        }
    }

    override val postgresObjects = postgresObjects {
        uniqueConstraint("pipeline_versions_pipeline_version_uq", pipelineId, version)

        rowTrigger("pipeline_versions_guard_published") {
            before(PostgresTriggerEvent.UPDATE, PostgresTriggerEvent.DELETE)

            onDelete {
                require(old[status] eq PipelineVersionStatus.DRAFT) {
                    "published pipeline versions cannot be deleted"
                }
            }

            onUpdate {
                transition(status) {
                    from(PipelineVersionStatus.DRAFT)
                        .allows(PipelineVersionStatus.DRAFT, PipelineVersionStatus.PUBLISHED)
                        .otherwise("a draft must be published before retirement")
                }
                immutableWhen(
                    old[status] neq PipelineVersionStatus.DRAFT,
                    pipelineId,
                    version,
                    name,
                    message = "published pipeline versions are immutable",
                )
            }
        }
    }
}
```

`old[column]` and `new[column]` preserve the column's Kotlin type, including `DataEnum` storage.
Delete handlers expose only `old`, insert handlers expose only `new`, and update handlers expose
both. `immutableWhen` uses PostgreSQL's null-safe `IS DISTINCT FROM` row comparison. The DSL
generates and orders the PL/pgSQL function and trigger automatically; `sql(...)` is available as
an explicit escape hatch inside a handler.

To delete a referenced parent when its child row is deleted, chain `cascadeDeleteParent()` onto
a foreign-key column on a `DataTable` or `UuidDataTable`:

```kotlin
val attachmentId = fkey(
    Document::attachmentId,
    AttachmentsTable,
    onDelete = ReferenceOption.NO_ACTION,
).cascadeDeleteParent()
```

KEEP registers an `AFTER DELETE` row trigger using the foreign key's target table and column.
It works for nullable and non-nullable keys, including Long and UUID, and preserves the column's
property mapping. Null keys are skipped. Deletion runs in the same transaction as the child
deletion, including deletes issued outside KEEP. Generated trigger and function names are
deterministic and fit PostgreSQL's identifier limit; use `cascadeDeleteParent(triggerName = "...")`
to choose the trigger name.

The existing FK delete action is preserved. `fkey()` defaults to `CASCADE`, so omitting
`onDelete = ReferenceOption.NO_ACTION` enables deletion in both directions. The helper always
attempts to delete the parent; any other referencing rows apply their normal FK actions, which
can reject the deletion or cascade further. It does not check whether the parent is orphaned.
Composite or ambiguous foreign keys are rejected.

The same typed operation is available in an explicit `rowTrigger` delete handler:
`after(PostgresTriggerEvent.DELETE)` with `onDelete { deleteReferencedRow(attachmentId) }`.

Typed objects participate in fresh-install generation, `AppSchema.create()`, and migration
comparison. KEEP creates missing objects and replaces changed unique constraints, trigger
functions, or trigger definitions. Re-running migration planning after applying the generated SQL
is idempotent.

For a single column on a KEEP `DataTable` or `UuidDataTable`, use the chainable helper:

```kotlin
import org.tekfive.keep.data.uniqueNonNullUniqueIndex

val url = column(OpportunityArticle::url).uniqueNonNullUniqueIndex()
```

This enforces PostgreSQL 15+ `UNIQUE NULLS NOT DISTINCT`: non-null values must be unique, and
at most one row can have a null URL. An optional `customIndexName` sets the constraint/index name;
the default is `<table>_<column>_uq`. KEEP's schema creation and migration APIs collect it
automatically, alongside any `postgresObjects` declared on the table.

For composite keys, declare the constraint directly in a `DataTable` or `UuidDataTable` initializer:

```kotlin
import org.tekfive.keep.data.uniqueConstraint

init {
    uniqueConstraint(tenantId, alias, nullsNotDistinct = true)
}
```

The generated name is `<table>_<column1>_<column2>_uq`. To supply a name (or declare a
single-column constraint), use `uniqueConstraint("users_tenant_alias_uq", tenantId, alias,
nullsNotDistinct = true)`. These overloads register typed PostgreSQL constraints automatically;
no `postgresObjects` override is needed. The older columns-only overload without the
`nullsNotDistinct` argument continues to create an Exposed unique index.

Explicit `postgresObjects` declarations remain available:

```kotlin
override val postgresObjects = postgresObjects {
    uniqueConstraint("users_email_uq", email, nullsNotDistinct = true)
    // Composite keys also treat nulls as equal when comparing the entire key.
    uniqueConstraint("users_tenant_alias_uq", tenantId, alias, nullsNotDistinct = true)
}
```

The single-column constraint allows at most one null email. The composite constraint allows
one null alias per tenant, while different tenants can each have a null alias. The option defaults
to `false`, preserving PostgreSQL's usual behavior of allowing multiple nulls. Fresh-install
generation rejects this option for targets older than PostgreSQL 15, and migration planning
detects changes to the option and recreates the constraint. Existing conflicting rows must be
resolved before applying a migration that enables it.

## Next steps

Use [migrations](migrations.md) for an existing database. The [getting-started example](getting-started.md) shows a small schema created for local development.
