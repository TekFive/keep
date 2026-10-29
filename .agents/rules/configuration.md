---
description: Use Ack for all backend configuration and values that could reasonably become configuration.
paths:
  - "**/*.kt"
globs:
  - "**/*.kt"
---

# Configuration

Use `org.tekfive.ack.Ack` for anything that is configuration or could reasonably
become configuration. Declare it through Ack when writing the code, even if
the default is the only value currently needed. Do not wait for someone to
request an override.

A value is configuration when it could vary by environment, deployment,
external service, workload, or operator preference. Examples include:

- Timeouts, retry counts, retry delays, polling intervals, and schedules.
- Page sizes, batch sizes, concurrency, cache sizes, and retention periods.
- Size limits, validation limits, thresholds, model budgets, and other tuning values.
- Feature switches, default behavior, service URLs, ports, paths, and executable names.

Do not hardcode these values in `const val`, ordinary properties, constructor
defaults, or inline literals. Put defaults in their Ack declarations and use
the configured values wherever the behavior is applied.

## Declaring and reading settings

- Do not create a global or global relative "Settings" or "Configuration"
  type or class. Define `Ack` configuration in the object or companion object
  of the class that uses the configuration the most.
- Declare each setting once and reuse it. Follow the existing configuration
  owner for the subsystem instead of repeating keys and defaults.
- Use the appropriate typed Ack property, such as `Ack.int`, `Ack.long`,
  `Ack.boolean`, or `Ack.string`. Give it a descriptive uppercase key with a
  subsystem prefix and an explicit unit where relevant, such as `_SECONDS`
  or `_BYTES`.
- Supply a sensible default when one exists, a description, and supported
  validation bounds. Require an explicit value when no safe default exists.
  Validate relationships between settings when constructing the configured service.
- Keep the Ack property and invoke it after configuration sources are registered.
  Do not resolve it eagerly during class or object initialization. A service or
  operation may receive a snapshot of configured values through its constructor.

```kotlin
private val batchSize = Ack.int(
    "IMPORT_BATCH_SIZE",
    100,
    min = 1,
    description = "Maximum records processed in one import batch.",
)

fun importRecords(records: List<ImportRecord>) {
    val configuredBatchSize = batchSize()
    for (batch in records.chunked(configuredBatchSize)) {
        importBatch(batch)
    }
}
```

## Configuration sources and documentation

Register configuration sources through `AckRegistry` during application setup.
Application code must not read settings directly with `System.getenv`,
`System.getProperty`, a dotenv reader, or a separate configuration loader.
Do not catch an invalid setting and silently substitute a hardcoded value.

In tests, provide overrides through `MapSource` and restore the registry after
the test. Do not mutate the process environment. Tests may pass explicit values
to a constructor when the production caller supplies those values through Ack.

Document new operator settings in `backend/.env.example`, including their defaults
and units. Document required settings without including real credentials.

## Fixed constants

Constants are appropriate for fixed identities, protocol syntax, and mathematical
facts. They are not appropriate for choices about application behavior. If a value
could reasonably need tuning or an override, use Ack.
