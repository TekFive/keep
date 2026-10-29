# Paging and API list responses

[Usage guide index](README.md) · [KEEP README](../README.md)

[![JetBrains logo](https://resources.jetbrains.com/storage/products/company/brand/logos/jetbrains.svg)](https://www.jetbrains.com/exposed/)

[![PostgreSQL logo](https://www.postgresql.org/media/img/about/press/elephant.png)](https://www.postgresql.org/)

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

`AppSchema` is the legacy lifecycle-oriented subclass of `KeepSchema`. It groups tables, extensions, sequences, and post-create SQL for bootstrap, tests, and local setup. Since it inherits `KeepSchema`, it can also be passed to `PostgresMigrationGenerator` while applications transition to the declarative API.

```kotlin
object PatientSchema : AppSchema() {
    override val extensions = listOf(KeepSchema.CITEXT)
    override val tables = listOf(PatientsTable, JobRecordsTable, JobHeartbeatsTable, LocksTable)
}

db {
    PatientSchema.create()
}
```

Schema creation applies `KeepSchema.types` and the typed `postgresObjects` declared by KEEP tables.

## Next steps

See [tables](tables.md) for mapping, [PostgreSQL queries](postgresql.md) for search expressions, and [schemas](schema.md) for installation.
