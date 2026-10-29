# PostgreSQL columns and queries

[Usage guide index](README.md) · [KEEP README](../README.md)

[![PostgreSQL logo](https://www.postgresql.org/media/img/about/press/elephant.png)](https://www.postgresql.org/)

[![JetBrains logo](https://resources.jetbrains.com/storage/products/company/brand/logos/jetbrains.svg)](https://www.jetbrains.com/exposed/)

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

Native PostgreSQL full-text search is available through `org.tekfive.keep.text`:

```kotlin
import org.tekfive.keep.text.*

object Articles : Table("articles") {
    val title = text("title")
    val searchVector = tsVector("search_vector")

    init {
        index("articles_search_gin", false, searchVector, indexType = "GIN")
    }
}

// Inside a transaction, populate the vector using PostgreSQL's text parser.
Articles.insert {
    it[title] = "Cats chase rats"
    it[searchVector] = toTsVector("Cats chase rats", configuration = "english")
}

val query = webSearchToTsQuery("cats -dogs", configuration = "english")
val rank = Articles.searchVector.tsRank(query)
val results = Articles.selectAll()
    .where { Articles.searchVector matches query }
    .orderBy(rank to SortOrder.DESC)
```

`tsVector(name)` returns `Column<TsVector>`; `.nullable()` is supported. `TsVector.value`
contains PostgreSQL's serialized lexemes and positions, not the original document text.
Property-based declarations infer nullability, default to a snake-case column name, and retain
the property mapping even when the database column has a different name:

```kotlin
import org.tekfive.keep.data.column

val searchVector = column(Article::searchVector)
val optionalSearch = column(Article::optionalSearchVector, name = "optional_search")
```

`toTsVector` accepts a string or a text expression, including nullable expressions (SQL NULL
stays NULL). Use `toTsVector(Articles.title, "english")` to rebuild vectors in an update.
Writes to source text columns do not automatically update a stored vector; write both in the
same transaction or define a database trigger/generated column separately.

Query helpers include `plainToTsQuery` (all words), `phraseToTsQuery` (ordered phrase),
`webSearchToTsQuery` (quoted phrases, OR, minus terms), and `toTsQuery` (PostgreSQL query syntax,
including prefix matching). `matches` emits `@@`; `tsRank` and `tsRankCd` return scores and
accept a normalization bitmask. Nullable vector rankings remain nullable. Text and configuration
arguments are bound parameters. Specify the same configuration when parsing vectors and queries;
omitting it uses the server's `default_text_search_config`.

`TsQuery` stores serialized query syntax and can be passed directly to `matches`.
`tsQuery(name)` registers a column for saved queries. Both types support property declarations
such as `column(Model::searchVector)`, nullable properties, defaults, fresh installation, and
dynamic migrations. No PostgreSQL extension is required. See PostgreSQL's
[full-text search controls](https://www.postgresql.org/docs/current/textsearch-controls.html) and
[index documentation](https://www.postgresql.org/docs/current/textsearch-tables.html).

PostgreSQL array helpers include `includes` for `= ANY(array_column)` checks and `arrayILike` for case-insensitive text searches across string arrays.

```kotlin
val query = WorkflowRevisionsTable
    .selectAll()
    .where { WorkflowRevisionsTable.serviceReferences includes serviceId }
```

For a `Set<java.util.UUID>` or `List<java.util.UUID>` column, `containsAll` checks that every
requested UUID occurs in the stored array, allowing additional stored members:

```kotlin
import org.tekfive.keep.array.containsAll

val requiredIds = setOf(firstId, secondId)
val exists = MyTable.rowExists(MyTable.memberIds containsAll requiredIds)
```

This uses PostgreSQL `@>` with a bound UUID-array parameter. Order and duplicate counts do not
matter. Both nullable and non-nullable columns are supported. An empty requested collection
matches every non-null array, including an empty array; SQL NULL never matches. `containsAll`
checks containment, not exact set equality. It accepts any `Collection<java.util.UUID>` and
copies the input when constructing the predicate.

`DataEnum` list/set columns support all three membership operators using the enum's stable
integer ID:

```kotlin
import org.tekfive.keep.array.includes
import org.tekfive.keep.array.intersects
import org.tekfive.keep.array.containsAll

val hasActive = MyTable.statuses includes Status.ACTIVE
val hasAny = MyTable.statuses intersects setOf(Status.ACTIVE, Status.PENDING)
val hasAll = MyTable.statuses containsAll listOf(Status.ACTIVE, Status.PENDING)
```

These work with `column(::property)`, `dataEnumList`, and `dataEnumSet`, including nullable
columns. `includes` binds one integer ID; `intersects` uses integer-array overlap (`&&`);
`containsAll` binds an integer array (`@>`). Empty input never matches for `intersects` and
matches every non-null array for `containsAll`. SQL NULL never matches. The existing
`enumIntersects` function remains an alias for `intersects`. Encrypted enum lists are stored
as binary values and do not support these array operators.

Schema helpers also provide common timestamp, active, description, foreign-key, and unique-constraint conventions.

## Next steps

Declare required extensions in your [schema](schema.md), then manage changes with [migrations](migrations.md).
