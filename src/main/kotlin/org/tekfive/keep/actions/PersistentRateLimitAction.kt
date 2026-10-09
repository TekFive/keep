package org.tekfive.keep.actions

import org.tekfive.keep.counter.CounterTable
import org.tekfive.keep.counter.CountersTable
import org.tekfive.kviash.exchange.Exchange
import org.tekfive.kviash.exchange.ExchangeAction
import java.time.Clock

/**
 * PostgreSQL-backed fixed-window rate limiting shared across application instances.
 * Register as a KViash pre-action and include [counters] in the application's schema.
 * Counters use the scope `rate:<scope>`; rejected requests also increment the counter.
 * The default key is the client IP. Supply [clientKeyExtractor] for user/tenant identifiers
 * or application-owned pseudonymization. Keys are stored as supplied, without normalization.
 * Run before a request's business transaction if rejected/failed requests must remain counted.
 * [maxRequests] and [windowMillis] are evaluated once per request and must return positive values.
 * Limit changes apply immediately; duration changes apply when a new counter window starts.
 * Existing windows retain their stored expiry.
 */
class PersistentRateLimitAction(
    scope: String,
    private val maxRequests: () -> Int,
    private val windowMillis: () -> Long,
    private val counters: CounterTable = CountersTable,
    private val clock: Clock = Clock.systemUTC(),
    private val clientKeyExtractor: (Exchange) -> String = { it.request.clientIp },
) : ExchangeAction {
    constructor(
        scope: String,
        maxRequests: Int,
        windowMillis: Long,
        counters: CounterTable = CountersTable,
        clock: Clock = Clock.systemUTC(),
        clientKeyExtractor: (Exchange) -> String = { it.request.clientIp },
    ) : this(scope, { maxRequests }, { windowMillis }, counters, clock, clientKeyExtractor) {
        require(maxRequests > 0) { "maxRequests must be greater than zero" }
        require(windowMillis > 0) { "windowMillis must be greater than zero" }
    }

    private val counterScope = "rate:$scope"

    init {
        require(scope.isNotBlank() && counterScope.length <= 128) { "scope must contain 1 to 123 characters" }
    }

    override fun invoke(exchange: Exchange): Any? {
        val maxRequests = maxRequests()
        val windowMillis = windowMillis()
        require(maxRequests > 0) { "maxRequests must be greater than zero" }
        require(windowMillis > 0) { "windowMillis must be greater than zero" }

        val counter = counters.increment(
            scope = counterScope,
            key = clientKeyExtractor(exchange),
            windowMillis = windowMillis,
            now = clock.millis(),
        )
        val remaining = (maxRequests.toLong() - counter.count).coerceAtLeast(0)
        exchange.response.addHeader("X-RateLimit-Limit", maxRequests.toString())
        exchange.response.addHeader("X-RateLimit-Remaining", remaining.toString())
        if (counter.count > maxRequests) {
            val remainingMillis = (checkNotNull(counter.expiresAt) - clock.millis()).coerceAtLeast(1)
            val retryAfter = (remainingMillis - 1) / 1000 + 1
            exchange.response.addHeader("Retry-After", retryAfter.toString())
            exchange.response.sendStatus(429)
        }
        return null
    }
}
