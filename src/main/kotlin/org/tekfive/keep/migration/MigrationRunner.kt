package org.tekfive.keep.migration

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import org.tekfive.keep.db.db
import org.tekfive.keep.db.dbCommit
import java.net.InetAddress

/**
 * Applies database migrations supplied as an ordered list. Safe to call
 * concurrently from multiple JVM instances connected to the same database:
 * a PostgreSQL session-level advisory lock serializes peers for each migration.
 * Re-reading history inside the lock (after acquiring it) makes the
 * "did a peer already apply this?" check race-free.
 *
 * Session-level locks (not transaction-scoped) are used because DDL statements
 * executed inside [Migration.apply] — e.g. via [SchemaUtils.create] — may commit
 * the current transaction, which would release a transaction-scoped lock early.
 * Session-level locks survive individual transaction commits and are released
 * explicitly after the history row is committed.
 *
 * Fresh database: the runner first ensures [MigrationHistoryTable] exists,
 * then walks the supplied list in ascending [Migration.version] order,
 * applying every entry whose version is not yet in history.
 *
 * Existing database: the same code path runs — already-applied versions
 * are simply filtered out.
 */
object MigrationRunner {

    /**
     * Fixed key for postgres advisory locks. Any positive 64-bit constant
     * unique to this codebase works; the value itself has no meaning beyond
     * "the migration lock".
     */
    private const val ADVISORY_LOCK_KEY: Long = 4_705_485_869_215_232_402L

    private val log = LoggerFactory.getLogger(MigrationRunner::class.java)

    fun run(migrations: List<Migration>) {
        validate(migrations)

        // Bootstrap history table on first-ever run. Idempotent.
        db { SchemaUtils.create(MigrationHistoryTable) }

        val sorted = migrations.sortedBy { it.version }
        for (migration in sorted) {
            applyOne(migration)
        }
    }

    private fun applyOne(migration: Migration) {
        db {
            val tx = TransactionManager.current()

            // Session-level advisory lock: survives transaction commits that DDL inside
            // apply() may trigger (e.g. SchemaUtils.create commits the transaction).
            // Must be released explicitly with pg_advisory_unlock on the same connection
            // after the history row has been committed. The try/finally guarantees the
            // lock is released even if apply() throws — without it the rolled-back
            // connection would return to the HikariCP pool with the lock still held,
            // poisoning subsequent borrowers.
            tx.exec("SELECT pg_advisory_lock($ADVISORY_LOCK_KEY)")
            try {
                val alreadyApplied = MigrationHistoryTable
                    .selectAll()
                    .where { MigrationHistoryTable.version eq migration.version }
                    .any()

                if (alreadyApplied) {
                    log.debug("Migration V{}: {} already applied; skipping", migration.version, migration.name)
                    return@db
                }

                log.info("Applying migration V{}: {}", migration.version, migration.name)
                migration.apply(tx)

                // apply() may have committed the current transaction (e.g. via SchemaUtils.create).
                // Insert the history row in whatever transaction state we are now in.
                MigrationHistoryTable.insert {
                    it[version] = migration.version
                    it[name] = migration.name
                    it[appliedAt] = System.currentTimeMillis()
                    it[appliedBy] = appliedByIdentifier()
                }

                // Commit the history row BEFORE releasing the lock so that any peer waiting
                // on the lock will see the completed migration when it reads history.
                dbCommit()
            } catch (t: Throwable) {
                rollbackBeforeUnlock(tx, t)
                throw t
            } finally {
                releaseLock(tx)
            }
        }
    }

    private fun rollbackBeforeUnlock(tx: JdbcTransaction, cause: Throwable) {
        try {
            tx.rollback()
        } catch (e: Exception) {
            cause.addSuppressed(e)
            log.warn("Failed to roll back migration transaction before releasing advisory lock", e)
        }
    }

    /**
     * Best-effort lock release. Runs in a finally block so it executes even
     * when the surrounding transaction is rolling back. Postgres advisory
     * unlock operates on the connection session, not the transaction, so it
     * succeeds regardless of transaction state. Failure here is logged but
     * not rethrown — we already have a primary exception to propagate.
     */
    private fun releaseLock(tx: JdbcTransaction) {
        try {
            tx.exec("SELECT pg_advisory_unlock($ADVISORY_LOCK_KEY)")
        } catch (e: Exception) {
            log.warn("Failed to release migration advisory lock; connection may be poisoned", e)
        }
    }

    private fun validate(migrations: List<Migration>) {
        if (migrations.isEmpty()) {
            throw MigrationConfigurationException("No migrations supplied to MigrationRunner.run")
        }
        val duplicates = migrations.groupingBy { it.version }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw MigrationConfigurationException("Duplicate migration versions: $duplicates")
        }
        migrations.firstOrNull { it.version <= 0 }?.let {
            throw MigrationConfigurationException(
                "Migration version must be > 0; got ${it.version} for ${it.name}"
            )
        }
    }

    private fun appliedByIdentifier(): String =
        runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown")
}
