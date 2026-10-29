# Counters and rate limits

[Usage guide index](README.md) · [KEEP README](../README.md)

[![PostgreSQL logo](https://www.postgresql.org/media/img/about/press/elephant.png)](https://www.postgresql.org/)

`org.tekfive.keep.counter.CountersTable` stores atomic PostgreSQL counters shared across application
instances. Add it to your `KeepSchema.tables` (or create it with Exposed's `SchemaUtils`). To use a
different table name, declare `object UsageCounters : CounterTable("usage_counters")` and register
that table instead.

Each counter has a case-sensitive scope and key. Scopes support up to 128 characters, keys up to
512. Counts and increments are `Long` values; increments must be positive.

```kotlin
// A cumulative total without expiry.
CountersTable.increment("processed-bytes", fileId, amount = byteCount)

// A fixed window beginning with the first increment, resetting at expiry.
val quota = CountersTable.increment("daily-exports", tenantId, windowMillis = 86_400_000)

// An inactivity window whose expiry is refreshed with each increment.
val failures = CountersTable.increment("login-failures", userId, windowMillis = 900_000, sliding = true)
val stored = CountersTable.get("login-failures", userId)
val activeCount = stored?.takeUnless { it.isExpired() }?.count ?: 0L
CountersTable.clear("login-failures", userId)

// Schedule periodically; never-expiring counters are retained.
CountersTable.clearExpired()
```

`get` returns stored counters even after expiry. Incrementing an expired counter resets its count
and window atomically. Fixed windows retain their original expiry; sliding expiry is an inactivity
timeout, not a rolling-window sum. Keep the expiry policy consistent per scope, or clear a counter
before changing it. Operations join the current transaction or open one using `db`; an enclosing
rollback also rolls back counter changes.

`org.tekfive.keep.actions.PersistentRateLimitAction` provides a KViash pre-action using these counters:

```kotlin
val loginLimit = PersistentRateLimitAction("login", maxRequests = 10, windowMillis = 60_000)

// Read current configuration on every request.
val dynamicLimit = PersistentRateLimitAction(
    "api",
    maxRequests = { settings.maxRequests },
    windowMillis = { settings.windowMillis },
)

// Optional key extractor and counter table for a shared tenant budget.
val tenantLimit = PersistentRateLimitAction(
    "exports", maxRequests = 100, windowMillis = 60_000, counters = UsageCounters,
) { exchange -> exchange.request["tenantId"].toString() }

// Supply loginLimit, dynamicLimit, or tenantLimit in your route's preActions.
```

The action defaults to the client IP and uses scope `rate:<scope>` (leaving 123 characters for the
supplied scope). It adds `X-RateLimit-Limit` and `X-RateLimit-Remaining`, and responds with HTTP 429
plus `Retry-After` when the limit is exceeded. Rejected requests also count. Keys are stored as
supplied; provide an application-owned key extractor if identifiers need pseudonymization.
Run the action before the request's business transaction when failed requests must remain counted.

The closures are evaluated once per request, and both values must be positive before the counter
is changed. Request-limit changes take effect immediately. Window-duration changes apply to new
windows; active windows retain their stored expiry, which is also used for `Retry-After`.
The fixed-value constructor remains available and validates its arguments at construction time.

## Next steps

Register counter tables in your [schema](schema.md), and review [transaction boundaries](database.md) when counting failed requests.
