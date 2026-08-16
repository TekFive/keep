# KEEP

KEEP stands for **K**otlin **E**xposed **E**nhancements for **P**ostgreSQL.

A Kotlin persistence utility library built on JetBrains Exposed. KEEP provides table/data mapping helpers, PostgreSQL-specific column and query extensions, migration tracking, transaction-scoped tuple caching, database-backed locks, encrypted column types, paging helpers, and a lightweight database-backed job runner.

## Design Priorities

### Exposed First

KEEP extends Exposed instead of replacing it. Tables remain ordinary Exposed table objects, queries still use Exposed DSL expressions, and transactions still run through Exposed's transaction manager. KEEP adds conventions for common application patterns such as ID-backed data classes, standard foreign key names, JSON columns, active flags, timestamps, and paged query responses.

### PostgreSQL-Oriented

The library assumes PostgreSQL for its richer database features. It includes helpers for arrays, JSONB paths, CITEXT, PostGIS coordinate values, row locking, and SQLSTATE-aware retry behavior. The test suite uses PostgreSQL through Testcontainers so integration behavior is checked against a real database.

### Operational Defaults

KEEP includes small operational building blocks that application code usually needs around persistence: idempotent migration execution, transaction cache scoping, advisory-style lock rows, encrypted database values, and persisted background jobs with retries, scheduling, timeout detection, and job logs.

## Requirements

- Java 21+
- Kotlin 2.x
- PostgreSQL for runtime use
- Docker or a compatible Testcontainers environment for integration tests

## Installation

Add JitPack to your dependency repositories:

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}
```

Then add KEEP:

```kotlin
implementation("com.github.TekFive:keep:v1.0.0")
```

KEEP resolves its ACK, JFK, and KViash dependencies from JitPack. The local Maven repository is checked first, allowing a locally published artifact with the same JitPack coordinates to override a remote artifact.

## Development

Run the test suite:

```bash
./gradlew test
```

To develop against sibling source checkouts instead of JitPack artifacts, place ACK, JFK, and KViash beside this repository and enable local-project substitution:

```bash
./gradlew -Pkeep.useLocalProjects=true test
```

`KEEP_USE_LOCAL_PROJECTS=true` provides the same switch for environments where an environment variable is more convenient.

Publish to the local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Generate a jar:

```bash
./gradlew jar
```

## Major Features

### Data Table Helpers

KEEP provides `Data`, `DataTable`, `DataTuple`, and related table abstractions for mapping Kotlin objects to Exposed tables. Table column property names are matched to primary constructor property names. The managed `id` column is handled by `DataTable`, so domain classes focus on business fields.

Immutable `val` properties are written when a row is inserted. Mutable `var` properties are tracked after create, update, and load operations, so `update` can write only changed fields.

```kotlin
class Patient(
    val mrn: String,
    var displayName: String,
    var active: Boolean,
) : Data()

object PatientsTable : DataTable<Patient>("patients") {
    val mrn = varchar("mrn", 64).uniqueIndex()
    val displayName = name("display_name")
    val active = active()
}

val patient = PatientsTable.create(
    Patient(mrn = "MRN-001", displayName = "Ada Lovelace", active = true)
)

patient.displayName = "Ada Byron"
PatientsTable.update(patient)
```

Common operations include `create`, `save`, `update`, `delete`, `getById`, `findById`, `findByIds`, and `findByUnique`. `Data` instances also expose dirty-property information and JSON serialization helpers.

### Transaction Utilities

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

### PostgreSQL Extensions

KEEP includes PostgreSQL-specific column types and expressions for features that are useful in application schemas but awkward to express repeatedly through plain Exposed APIs.

Text helpers include `citext`, `name`, `emailAddress`, `phoneNumber`, and `ilike`. JSON helpers include `jsonValue`, `jsonObject`, `jsonArray`, `toFromJson`, and `toFromJsonArray`.

```kotlin
object PatientProfilesTable : DataTable<PatientProfile>("patient_profiles") {
    val email = emailAddress()
    val displayName = name("display_name")
    val metadata = jsonObject("metadata")
}

val matches = db {
    PatientProfilesTable
        .selectAll()
        .where { PatientProfilesTable.displayName ilike "ada" }
        .toList()
}
```

PostgreSQL array helpers include `includes` for `= ANY(array_column)` checks and `arrayILike` for case-insensitive text searches across string arrays.

```kotlin
val query = WorkflowRevisionsTable
    .selectAll()
    .where { WorkflowRevisionsTable.serviceReferences includes serviceId }
```

Schema helpers also provide common timestamp, active, description, foreign-key, and unique-constraint conventions.

### Migration Runner

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

### Database Locks

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

### Encryption Support

KEEP uses Tink AEAD primitives for encrypted database values. `DatabaseEncryptionProvider` is configured during application bootstrap and then encrypted column types use the active AEAD automatically.

```kotlin
DatabaseEncryptionProvider.configure(
    KeysetLoader.Config(
        mode = EncryptionKeysetMode.SEALED,
        file = Path.of("/var/lib/aideway/keyset.sealed"),
    )
)
DatabaseEncryptionProvider.ensureInitialized()
```

Encrypted column helpers include encrypted text, binary, enum, JSON object, JSON container, and object-list mappings.

```kotlin
class ApiToken(
    val name: String,
    var token: String,
) : Data()

object ApiTokensTable : DataTable<ApiToken>("api_tokens") {
    val name = name(unique = true)
    val token = encryptedText("token")
}
```

Keyset loading supports three modes:

- `PLAINTEXT`: cleartext Tink JSON keyset on disk, intended for local development.
- `SEALED`: sealed keyset file unwrapped through Clevis/TPM, intended for production.
- `RECOVERY`: passphrase-based recovery file using Argon2id and authenticated encryption.

Recovery-file parsing includes size and Argon2 parameter limits to avoid unbounded local resource use from malformed files.

### Job Runner

The job subsystem stores work in PostgreSQL and dispatches registered `JobSpec` implementations through `JobCoordinator`. It records attempts, states, start/end timestamps, failure details, and job logs.

```kotlin
class SendReminderJob : Job {
    override fun execute(context: JobContext): JobResult {
        context.log.info("sending reminder for job ${context.jobId}")
        sendReminder(context.details)
        return JobCompleted()
    }

    companion object : JobSpec {
        override val jobTypeIdentifier = "send-reminder"
        override val maxRetriesOnFailure = 3
        override val minSecondsBetweenRetries = 300
        override fun createJob(): Job = SendReminderJob()
    }
}
```

Jobs are inserted into `JobRecordsTable`, then a coordinator polls for runnable work and dispatches it.

```kotlin
val registry = JobRegistry().apply {
    register(SendReminderJob)
}

val coordinator = JobCoordinator(
    systemIdentifier = "worker-1",
    registry = registry,
    configuration = DefaultJobConfiguration(),
)

coordinator.start()

JobRecordsTable.insertJob(
    spec = SendReminderJob,
    details = mapOf("patientId" to patientId).toJsonObject(),
)
```

Job records support priority ordering, minimum start times, retry policies, lock keys, concurrency limits, scheduled jobs, timeout detection, and average-runtime estimation using JSON path filters over previous job details.

### Paging and Schema Helpers

Paging utilities provide a consistent shape for API list endpoints. `PageRequest` parses request parameters such as `page`, `size`, `q`, and `sort`, while `PagedQuery` handles filtering, search predicates, sorting, limits, offsets, and JSON response formatting.

```kotlin
class PatientsQuery(parameters: HttpRequestParameters) : PagedQuery(PatientsTable, parameters) {
    init {
        returnColumns(PatientsTable.id, PatientsTable.mrn, PatientsTable.displayName, PatientsTable.active)
        addSearchedColumns(PatientsTable.mrn, PatientsTable.displayName)
        setDefaultSort(PatientsTable.displayName)
    }

    override fun filters(parameters: HttpRequestParameters): List<Op<Boolean>> {
        return listOfNotNull(
            parameters["active"]?.toBooleanStrictOrNull()?.let { PatientsTable.active eq it }
        )
    }
}

val response = PatientsQuery(request.parameters).toJsonObject()
```

`AppSchema` groups tables, extensions, sequences, and post-create SQL for an application-owned schema. It is useful for bootstrap, tests, migrations, and local setup.

```kotlin
object PatientSchema : AppSchema() {
    override val extensions = listOf(CitextColumnType.Extension)
    override val tables = listOf(PatientsTable, JobRecordsTable, LocksTable)
}

db {
    PatientSchema.create()
}
```

Schema creation handles common KEEP table hooks such as custom types, custom indices, and post-schema SQL defined by `DataTable` implementations.
