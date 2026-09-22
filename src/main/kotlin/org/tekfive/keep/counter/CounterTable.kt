package org.tekfive.keep.counter

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.booleanLiteral
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import org.tekfive.keep.db.db

/**
 * Atomic, shared counters for quotas, usage totals, retry tracking, and rate limits.
 * Add the table to the application's schema before use. Operations join an existing transaction
 * or start one through [db]; an enclosing rollback also rolls back counter changes.
 * Scopes and keys are case-sensitive and stored as supplied.
 */
open class CounterTable(name: String) : Table(name) {
    val scope = varchar("scope", 128)
    val counterKey = varchar("counter_key", 512)
    val counterValue = long("counter_value")
    val windowStartedAt = long("window_started_at")
    val expiresAt = long("expires_at").nullable().index()

    override val primaryKey = PrimaryKey(scope, counterKey)

    /**
     * Adds [amount], starting again from [amount] when the previous window has expired.
     * A null [windowMillis] creates a counter without expiry. Fixed windows retain their original
     * expiry; [sliding] refreshes expiry on every increment (an inactivity timeout, not a rolling sum).
     * Use a consistent expiry policy per scope, or [clear] a counter before changing its policy.
     * [now] and stored timestamps are epoch milliseconds. Expiry is inclusive: expiresAt <= now.
     */
    fun increment(
        scope: String,
        key: String,
        windowMillis: Long? = null,
        amount: Long = 1,
        sliding: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): Counter {
        validateKey(scope, key)
        require(amount > 0) { "amount must be greater than zero" }
        require(windowMillis == null || windowMillis > 0) { "windowMillis must be greater than zero" }
        require(!sliding || windowMillis != null) { "sliding requires windowMillis" }
        val expiry = windowMillis?.let { Math.addExact(now, it) }

        return db {
            // PostgreSQL locks the conflicting row and increments its current value in one statement.
            val row = upsertReturning(
                returning = listOf(counterValue, windowStartedAt, expiresAt),
                onUpdate = {
                    val lapsed = expiresAt lessEq insertValue(windowStartedAt)
                    it[counterValue] = case().When(lapsed, insertValue(counterValue))
                        .Else(counterValue + insertValue(counterValue))
                    it[windowStartedAt] = case().When(lapsed, insertValue(windowStartedAt)).Else(windowStartedAt)
                    it[expiresAt] = case().When(lapsed or booleanLiteral(sliding), insertValue(expiresAt)).Else(expiresAt)
                },
            ) {
                it[this@CounterTable.scope] = scope
                it[counterKey] = key
                it[counterValue] = amount
                it[windowStartedAt] = now
                it[expiresAt] = expiry
            }.single()

            counter(row)
        }
    }

    /** Returns the stored counter, including expired windows; use [Counter.isExpired] to check it. */
    fun get(scope: String, key: String): Counter? {
        validateKey(scope, key)
        return db {
            selectAll().where { (this@CounterTable.scope eq scope) and (counterKey eq key) }
                .singleOrNull()?.let(::counter)
        }
    }

    fun clear(scope: String, key: String): Int {
        validateKey(scope, key)
        return db {
            deleteWhere { (this@CounterTable.scope eq scope) and (counterKey eq key) }
        }
    }

    /** Deletes expired rows across all scopes. Run periodically from an application-owned job. */
    fun clearExpired(now: Long = System.currentTimeMillis()): Int = db {
        deleteWhere { expiresAt lessEq now }
    }

    private fun validateKey(scope: String, key: String) {
        require(scope.isNotBlank() && scope.length <= 128) { "scope must contain 1 to 128 characters" }
        require(key.isNotEmpty() && key.length <= 512) { "key must contain 1 to 512 characters" }
    }

    private fun counter(row: ResultRow): Counter =
        Counter(row[counterValue], row[windowStartedAt], row[expiresAt])

    data class Counter(val count: Long, val windowStartedAt: Long, val expiresAt: Long?) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAt?.let { it <= now } ?: false
    }
}
