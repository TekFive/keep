---
description: Kotlin coding conventions for Hilda
paths:
  - "**/*.kt"
globs:
  - "**/*.kt"
---

# Kotlin style

Follow the [official Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
This file lists only where Hilda differs from them or is stricter; where they conflict, this file
wins. Persistence rules are in [persistence conventions](persistence.md) and general comment rules are in
[comments](comments.md).

ktlint and detekt are configured in `backend/.editorconfig` and `backend/config/detekt.yml`. After
editing, run `make format-backend`, then `make check-backend` (ktlint, detekt, and tests).

Most rules below exist for one reason: **favor the form you can debug.** A breakpoint should be
able to stop on a single exit, a single iteration, or a single branch, with each intermediate value
held in a named local.

## Requires user permission

The practices below are not allowed by default. Existing uses in the codebase do not grant
permission for new ones. If one seems like the right tradeoff, stop and ask. Say what you want to do
and where (file, line, dependency name), why the default approach does not work, and what you tried
instead. If you are running unattended, don't use the practice: implement the allowed approach, or
leave that part undone and list it in your final report as blocked on permission.

When the user approves, record it at the site with `// Approved: <reason>`. An approval covers only
that case.

### Non-null assertion `!!`

Not in production code; tests may use it freely. Capture the value in a local, then check it, so a
debugger can show the value and the failure explains itself:

```kotlin
// Bad
val opportunity = Opportunity.findById(id)!!

// Good
val opportunity = Opportunity.findById(id)
if (opportunity == null) {
    throw IllegalStateException("Opportunity $id not found.")
}

// Good
val found = Opportunity.findById(id)
val opportunity = requireNotNull(found) { "opportunity $id not found" }
```

### `lateinit`

Only for test setup. Production wiring is explicit constructor passing (see `backend/AGENTS.md`).

### Silencing tools

- Don't add `@Suppress`, `@file:Suppress`, `// ktlint-disable`, or `// noinspection`.
- Don't weaken lint config: no edits to `detekt.yml` or `.editorconfig`, no detekt baseline entries,
  no turning off detekt's `warningsAsErrors`.
- Fix the code instead.

### Dependencies

- Don't add a library, plugin, or BOM to `build.gradle.kts`, `settings.gradle.kts`, or
  `gradle/libs.versions.toml`. Replacing one library with another counts as adding one.
- Before asking, check whether the standard library or an existing dependency (Keep, JFK, ack,
  kviash, Exposed, Jackson, ...) covers the need, and say which ones you checked.

## Control flow

### Early returns and `if`

- No one-line `if` or `if`/`else` statements. Braces always, with the body on its own line.
- Every early `return`, `return@label`, `continue`, or `break` sits on its own line, so a breakpoint
  can stop on the exit independently of the condition.
- An elvis early exit goes on the next indented line.
- Logging or metrics before an early return go on separate lines inside the guard block.

```kotlin
// Bad
if (notice.archivedAt != null) return null
val source = assertion.link.source ?: return null

// Good
if (notice.archivedAt != null) {
    return null
}

val source = assertion.link.source ?:
    return null
```

### Elvis `?:`

Use `?:` only for a simple fallback or an early-exit guard. It is simple when all of these hold:

- One `?:` in the expression. Never chain them.
- The left side is a variable, property, or single safe call (`notice?.title`).
- The right side is a literal, constant, plain variable, or a bare `return`, `continue`, `break`,
  `throw`, or `ApiError` factory call.
- It fits on one line, apart from the exit wrapped as above.

Everything else uses `if`/`else` and relies on smart casts. Never use `?: run { }`, `?: let { }`, or
`?.let { } ?: ...` in place of `if`/`else`.

```kotlin
// Bad
val organization = cache[id] ?: run {
    val loaded = Organization.findById(id)
    cache[id] = loaded
    loaded
}

// Good
val cached = cache[id]
val organization = if (cached != null) {
    cached
} else {
    val loaded = Organization.findById(id)
    cache[id] = loaded
    loaded
}
```

### Scope functions

`?.let { }` for a short nullable side effect and `also` are fine. `apply`, `run`, and `with` are
uncommon; don't reach for them by default.

### `when`

Use `when` to dispatch over sealed types and enums. Make a non-exhaustive `when` explicit with
`else -> {}`. Subject-less `when { }` is fine for prioritized boolean conditions.

### Loops instead of lambda chains

This replaces the official "prefer higher-order functions to loops" rule. A collection lambda chain
is allowed only when all of these hold:

- At most two operations.
- Each lambda body is a single expression: no nested lambdas, no `when`, no multi-branch `if`.
- No side effects (mutating outer state, I/O, logging).
- The chain fits on one line.

```kotlin
// OK
val activeIds = policies.filter { it.archivedAt == null }.map { it.id }

// Bad
val countsBySource = assertions
    .filter { it.publishedAt != null }
    .groupBy { it.link.source }
    .mapValues { (_, list) -> list.size }

// Good
val countsBySource = mutableMapOf<Source, Int>()
for (assertion in assertions) {
    if (assertion.publishedAt == null) {
        continue
    }
    val source = assertion.link.source
    countsBySource[source] = countsBySource.getOrDefault(source, 0) + 1
}
```

### Avoid functions as statements
Avoid the style of writing functions as statements. Instead of this:

```kotlin
fun getOptions(): AuthenticationOptionsDto = db {
    val tenants = if (AuthConfiguration.enabled(AuthType.MICROSOFT)) MicrosoftConnections.loginTenants() else emptyList()
    AuthenticationOptionsDto(
        passwordEnabled = AuthConfiguration.enabled(AuthType.PASSWORD),
        microsoftEnabled = tenants.isNotEmpty(),
        microsoftTenants = tenants.map { MicrosoftLoginTenantDto(it.id, it.name) },
    )
}
```
Do this:

```kotlin
fun getOptions(): AuthenticationOptionsDto {
    return db {
        val tenants = if (AuthConfiguration.enabled(AuthType.MICROSOFT)) MicrosoftConnections.loginTenants() else emptyList()
        AuthenticationOptionsDto(
          passwordEnabled = AuthConfiguration.enabled(AuthType.PASSWORD),
          microsoftEnabled = tenants.isNotEmpty(),
          microsoftTenants = tenants.map { MicrosoftLoginTenantDto(it.id, it.name) },
        )
    }
}
  ```


Also:

- Never use `forEach` or `forEachIndexed`. Use `for (item in items)` or
  `for ((index, item) in items.withIndex())`.
- Use `break`/`continue` instead of labeled returns from lambdas.
- Open-ended ranges use `..<`: `for (i in 0..<n)`, never `0..n - 1` or `until`.
- Operate eagerly. Don't use sequences; datasets are bounded.

## Functions

- Use block bodies with an explicit `return`, even for one-liners. Reserve expression bodies (`=`)
  for trivial computed `get()` properties.
- Use default arguments instead of overloads or builders; use delegating secondary constructors
  (`this(...)`) for convenience variants.
- Use named arguments for every `Boolean` parameter, for several parameters of the same primitive
  type, and when constructing multi-parameter objects.
- Add extension functions only when they give a domain type real vocabulary. Don't bolt general
  utilities onto stdlib types.
- Top-level functions are for DSLs (`db { }`, `json { }`). Business logic lives in methods.

## Types

- `data class` is for transient DTOs, value holders, and result types. Persisted entities and enums
  follow [persistence conventions](persistence.md).
- Use `sealed class` for closed result and variant hierarchies: nested `object`s for stateless
  variants, nested `class`es for stateful ones.
- Use `abstract class` for polymorphic bases with `open` members and sensible defaults or no-op
  hooks.
- Use `object` for singletons such as schema-only join tables, routing objects, and stateless
  dispatchers.
- Interfaces are capability mix-ins (`ToJsonObject`, `FromJsonObject`, `TrackUpdatedAt`), not
  service-contract abstractions.
- Don't use `@JvmInline` value classes.

## Mutability and collections

- Use `val` unless a value is genuinely reassigned. Persisted entities are the exception; see
  [persistence conventions](persistence.md).
- Public APIs and return types use read-only `List`, `Set`, and `Map`. Build results in a local
  mutable collection (or `buildList`/`buildMap`) and expose them as the read-only type. A mutable
  parameter is only for a collector the function fills.
- Optional fields are nullable with a `= null` default.

## Errors

- Controllers fail through the `ApiError` factories. They return `Nothing`, so they compose with
  elvis: `val opportunity = Opportunity.findById(id) ?: ApiError.onNotFound()`.
- Use `require(...) { }` and `check(...) { }` for preconditions and invariants. A `require` message
  a request can reach is returned to the caller, so it names a field or rule and never a value.
- Throw `IllegalStateException` or `IllegalArgumentException` for "should not happen" and
  unsupported-variant cases, with identifying context in the message.
- Use `try`/`catch` sparingly, at I/O and persistence boundaries. Log and degrade rather than let an
  infrastructure failure break a request.
- Don't use `kotlin.Result`. Surface failures through `Nothing`-returning factories or sealed
  `Failed` variants.

## Concurrency

- No coroutines and no `suspend` functions. Use platform or virtual threads
  (`Executors.newVirtualThreadPerTaskExecutor()`, direct `Thread` subclasses).
- Give every `Thread` a descriptive name (`"Projector[${source.name}]"`).
- Carry ambient request context in a `ThreadLocal` with push/pop framing.

## Logging

- Use SLF4J directly. Declare a private logger named `log`: `LoggerFactory.getLogger(Foo::class.java)`
  in concrete classes, `LoggerFactory.getLogger(javaClass)` in open or abstract bases so subclasses
  log under their own name.
- Use `{}` placeholders and pass the exception last:
  `log.error("Failed to project notice {} from {}", notice.id, source, e)`.

## Naming and files

- Packages are organized by domain, not by layer: a domain's model, table companion, and enums live
  together.
- Suffixes mean something: `*ApiController`, `*Routes`, `*Service` (stateful service), `*Registry`
  (in-memory type registry), `*Type` (abstract polymorphic base). Don't use `*Repository`,
  `*Manager`, or `*Wrapper`.
- No `Util.kt`, `Helpers.kt`, `Misc.kt`, or files that only hold extensions of one class.
- One top-level class or interface per file, named after it. A file may also hold small, tightly
  related declarations: a sealed hierarchy, a short private helper, a small value type or enum used
  only by that class, or extension functions for it. Don't combine unrelated or independently
  reusable classes to save files.
- Keep files under about 400 lines. When a file with several classes grows past that, split it into
  one file per class in the same package. A single class may run longer. An entity's companion-object
  table always stays in the entity's file ([persistence conventions](persistence.md)).
- Boolean computed properties start with `is` or `has`.
- Constants are `const val` in a companion object.
- Test functions use backtick names: `` fun `rejects duplicate source link`() ``.

## Formatting

ktlint enforces indentation, wildcard imports, and the rest of `.editorconfig`. On top of that:

- Lines are at most 120 columns, including indentation. Keep a construct on one line if it fits;
  wrap only when it doesn't. Exceptions: `package`/`import` lines, URLs in comments, unsplittable
  string literals.
- Trailing commas at every multi-line declaration and call site: parameters, arguments, enum
  entries, type parameters, and `when` entries with several conditions.
- Never align code horizontally.
- Separate the logical sections of a block with one blank line: validation, setup, the main work,
  and the result each read as their own paragraph. Never put two blank lines in a row, and don't
  add a blank line at the start or end of a block.
- Resolve import name clashes with an `as` alias.
- Use string templates, not `+` concatenation. For a literal `$` (JSON Schema, templates), use
  multi-dollar interpolation (`$$"""..."""`), not escaping.

## Comments and KDoc

- In KDoc, don't use `@param` or `@return`. Describe parameters in prose and link them with `[name]`.
- Document behavioral contracts that the signature can't show: cost (O()), side effects, and
  security or compliance rationale.
