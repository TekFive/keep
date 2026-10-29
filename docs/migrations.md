# Schema migrations

[Usage guide index](README.md) · [KEEP README](../README.md)

[![PostgreSQL logo](https://www.postgresql.org/media/img/about/press/elephant.png)](https://www.postgresql.org/)

[![JetBrains logo](https://resources.jetbrains.com/storage/products/company/brand/logos/jetbrains.svg)](https://www.jetbrains.com/exposed/)

On this page:

- [PostgreSQL Migration Generation](#postgresql-migration-generation)
- [Migration Runner](#migration-runner)
- [Next steps](#next-steps)

## PostgreSQL Migration Generation

`org.tekfive.keep.migration.dynamic.PostgresMigrationGenerator` compares a `KeepSchema` with an existing
PostgreSQL schema. It produces an ordered, typed `PostgresMigrationPlan` without committing schema
changes to the database. `toSql()` and `writeTo()` render the plan as a reviewable SQL script.

```kotlin
import org.tekfive.keep.migration.dynamic.PostgresMigrationGenerator

object ApplicationSchema : KeepSchema("public") {
    override val tables = listOf(UsersTable, OrdersTable)
    override val views = listOf(
        PostgresViewDefinition(
            name = "active_users",
            query = "SELECT id, email FROM users WHERE active",
        )
    )
    override val sequenceNames = listOf("order_number_seq")
}

val plan = PostgresMigrationGenerator.generate(
    database = database,
    keepSchema = ApplicationSchema,
    output = Path.of("migrations/V12__synchronize_schema.sql"),
    nonDestructive = true,
)

plan.suppressedStatements.forEach {
    println("Suppressed ${it.reason}: ${it.sql}")
}
```

`plan.statements` contains `PostgresMigrationStatement` values such as `CreateTable`, `RenameColumn`,
`AlterColumnType`, `AddConstraint`, `CreateIndex`, and `CreateOrReplaceView`. Each exposes the affected
objects and operation-specific fields. Suppressed entries retain the typed statement and its reason.
`plan.sqlStatements` provides the rendered strings when needed. The generator adapts Exposed DDL
strictly: unsupported syntax fails planning rather than becoming an opaque SQL statement.

The dynamic package also provides statements for sequences, functions, triggers, extensions, enum
types, identity/generated columns, and comments. These can be assembled into plans directly; support
for a statement does not imply the generator automatically infers that change. SQL expressions,
queries, and function bodies remain explicitly trusted fragments in `SqlExpression`, `SqlQuery`, and
`SqlBody`. Object identifiers are quoted separately.

Execute a plan after reviewing it:

```kotlin
val plan = PostgresMigrationGenerator.plan(database, ApplicationSchema, nonDestructive = true)
plan.writeTo(Path.of("migration.sql"))
val executedCount = plan.execute(database)

// Inside an existing Exposed/KEEP transaction:
db {
    plan.execute()
}
```

These are alternative execution forms; execute a generated plan once. `execute(database)` opens a
transaction or joins the caller's transaction on that database. `execute()` requires an existing
transaction. Both preflight the entire plan and execute atomically using a savepoint: a failed
statement rolls back the plan's preceding work while preserving the caller's earlier writes.
Joining a transaction does not commit it. Suppressed operations are never executed.

Operations marked `TransactionRequirement.AUTOCOMMIT`, including concurrent index creation/deletion
and enum-value additions, require `plan.executeAutocommit(database)`. This explicit mode opens a
dedicated connection and cannot run inside a caller transaction. Earlier successful statements stay
committed if a later statement fails. `PostgresMigrationExecutionException.statementIndex` identifies
the failed operation (zero-based), and `sql` identifies its rendered statement. Enum additions use
autocommit so subsequent operations can use the new values. Execution checks minimum PostgreSQL
versions before running any statements.

Dynamic execution does not record migration versions or acquire the versioned runner's advisory
lock. Use `MigrationRunner` with an application-owned `Migration` when version tracking is needed;
its `apply` method can call a transactional plan's `execute()`. Concurrent schema changes still need
application-level coordination.

The former generator and raw-SQL plan in `org.tekfive.keep.migration` have been removed. Update
imports to `org.tekfive.keep.migration.dynamic`; use `plan.statements` for typed operations or
`plan.sqlStatements` for rendered SQL strings.

Extensions declared in `KeepSchema.extensions` produce typed `CreateExtension` operations when
missing from the database, before dependent columns, tables, or views. Installed extensions are
recognized across schemas; undeclared extensions and extension-owned objects are preserved in
both modes. Names are safely quoted, and duplicate declarations produce one operation. Installation
uses PostgreSQL's default extension schema selection (the connection's search path unless the
extension specifies a schema), so use the same search path for planning and execution.

To compare extension-dependent types and views, planning temporarily installs missing extensions
inside a savepoint and always rolls back before returning, including on failure. Planning therefore
requires permission to install missing extensions, and their server-side extension files must be
available. Existing extensions are neither upgraded nor relocated automatically.

`KeepSchema` is authoritative for its PostgreSQL schema. Destructive mode can remove ordinary tables, views, materialized views, standalone sequences, and columns not declared by it. PostgreSQL-owned sequences for serial and identity columns remain managed by their tables.

With `nonDestructive = true`, KEEP suppresses statements that can remove stored data or undeclared schema objects, including table, column, undeclared-view, materialized-view, and sequence drops as well as column type rewrites, `DELETE`, and `TRUNCATE`. Structural replacement of a declared ordinary view is allowed because it stores no rows. Non-data-removing changes remain available, including adding objects, dropping indexes or constraints, and relaxing a column with `DROP NOT NULL`. Suppressed statements are returned separately and are not written into the executable SQL file.

When a column is removed from a declared table, a non-destructive plan retains the database column and its existing values, but removes its `NOT NULL` constraint so new inserts can omit it. Already-nullable columns require no change. PostgreSQL restrictions still apply to columns whose nullability is required by a primary key or identity; those need an explicit migration of the dependent definition.

Table objects without an explicit schema use PostgreSQL's current schema, which must match `KeepSchema.schemaName`.

`DataView` and `UuidDataView` require a `viewDefinition` declared in the view itself.
Register the view objects directly in `KeepSchema.views`:

```kotlin
class UserViewData(val email: String) : Data()

object AllUsers : DataView<UserViewData>("app.all_users") {
    val email = text("email")

    override val viewDefinition by lazy {
        view(UsersTable, ArchivedUsersTable) {
            UsersTable.select(UsersTable.id, UsersTable.email).unionAll(
                ArchivedUsersTable.select(ArchivedUsersTable.id, ArchivedUsersTable.email),
            )
        }
    }
}

object UserSchema : KeepSchema("app") {
    override val tables = listOf(UsersTable, ArchivedUsersTable)
    override val views = listOf(AllUsers)
}
```

Use `UuidData` with `UuidDataView` for UUID IDs. Existing mapped view subclasses must now
implement `viewDefinition`; omitting it is a compile-time error. The definition must use the
mapping's own name and schema. Keep view objects out of `KeepSchema.tables`.
Standalone `postgresView(name, sources...) { query }` definitions can also be included in
`views`, alongside mapped view objects. Plain Exposed `Table` mappings can use
`mapping.view(sources...) { query }` to produce a standalone definition.

Import `org.tekfive.keep.schema.view` / `postgresView` and Exposed's JDBC `select`, `union`,
or `unionAll` functions. Both `UNION` (deduplicated) and `UNION ALL` (duplicates retained) work,
along with joins, aliases, filters, and other Exposed query expressions. Query factories render
literal SQL when generating a plan; do not execute queries or perform other side effects inside them.
The query determines the view's output columns; use aliases when their names differ from the mapping.
List every source table or view, including sources in subqueries and every union branch. Sources
must be declared in the same `KeepSchema`. View dependencies determine creation order automatically.
Raw SQL `PostgresViewDefinition(name, query, references = listOf(...))` remains supported;
without references, list raw SQL views in dependency order for fresh installation.

Fresh installation and `AppSchema.create()` create views after tables and constraints;
`AppSchema.drop()` removes views before tables. Dynamic comparison checks PostgreSQL-normalized
queries and ordered output column names, types, and collations. Matching views are untouched.
Query-only changes with identical output structure use `CREATE OR REPLACE VIEW`. Structural
changes drop dependent views first and recreate them in dependency order, including in
non-destructive mode. Such replacements remove view-specific grants and other metadata;
query-only replacements retain them. Dependencies outside the managed schema are never cascaded.

Set `materialized = true` to declare a materialized view. Replacing one requires destructive mode;
if a materialized dependent prevents an ordinary view from being rebuilt, the entire affected
replacement is suppressed together. Planning validates view definitions against pending table
changes inside savepoints and always rolls back, including on failure. This briefly takes DDL
locks; materialized view queries are not executed during planning. Execute the returned plan
with its default transactional executor to apply replacements atomically.

Changing an existing identity column between an integer and UUID is intentionally not generated. That conversion requires an additive, application-specific migration that creates and backfills new primary-key and foreign-key columns before swapping them; KEEP reports this case instead of emitting an invalid PostgreSQL cast.

Declare historical SQL column names with `org.tekfive.keep.data.renamedFrom`:

```kotlin
val displayName = column(User::displayName)
    .renamedFrom("name", "full_name", "public_name")
```

These names are alternatives, not a sequence of migrations. Supply exact database names without
SQL quoting. If exactly one historical name exists and the current name is absent, KEEP emits
`ALTER TABLE ... RENAME COLUMN ... TO ...`, preserving the existing data. A database can upgrade
directly from any listed name. Retain historical names while supporting upgrades from those versions.

If only the current name exists, comparison proceeds normally. If none of the names exists, the
current column is created normally. Multiple historical columns, or a current column alongside a
historical column, cause an ambiguity error. Historical names cannot also be current names of
declared columns or be claimed by multiple columns on the same table. Metadata survives
`.nullable()` and `.transform()` and is available through the read-only `column.previousNames` list.

When renames are needed, the generator temporarily applies them inside a savepoint, compares the
remaining schema using the new names, and rolls back before returning—even if planning fails.
Earlier writes in the caller's transaction are preserved. The generated migration contains the
renames first, followed by changes to columns, indexes, constraints, and views; no second generation
pass is needed. Planning requires permission to rename the affected columns and takes PostgreSQL
table locks until the savepoint is rolled back, subject to configured lock and statement timeouts.
Renames are allowed with `nonDestructive = true`; type changes and other destructive operations
still follow the normal safety rules. Fresh installations use only the current names.

## Migration Runner

`MigrationRunner` applies ordered database migrations once and records each applied version in `MigrationHistoryTable`. It validates empty migration lists, duplicate versions, and non-positive versions before doing any migration work.

Each migration receives the active `JdbcTransaction`, so it can use Exposed APIs or raw SQL. Migrations are applied in ascending version order.

```kotlin
val migrations = listOf(
    object : Migration {
        override val version = 1L
        override val name = "create patients"

        override fun apply(tx: JdbcTransaction) {
            SchemaUtils.create(PatientsTable)
        }
    },
    object : Migration {
        override val version = 2L
        override val name = "add patient audit index"

        override fun apply(tx: JdbcTransaction) {
            tx.exec("CREATE INDEX IF NOT EXISTS patients_mrn_idx ON patients (mrn)")
        }
    },
)

MigrationRunner.run(migrations)
```

The runner uses a PostgreSQL session-level advisory lock so multiple application instances can call `MigrationRunner.run` during startup without racing. History is re-read after acquiring the lock so peers skip migrations that another instance already applied.

## Next steps

See [schema declarations](schema.md) for the source model, and [job upgrades](jobs.md#upgrading-existing-job-tables) for the ordered job migrations.
