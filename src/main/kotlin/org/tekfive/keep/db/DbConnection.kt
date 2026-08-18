package org.tekfive.keep.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.ack.Ack
import java.io.Closeable
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock

private const val VALIDATION_TIMEOUT_SECONDS = 5

fun dbTransactionAt(): Long = DbConnection.transactionAt


fun dbConnection(): Connection {
    return TransactionManager.current().connection.connection as Connection?
        ?: throw IllegalStateException("Current thread not running in database transactional context.")
}

fun dbCommitQuietly() {
    try {
        dbCommit()
    } catch (e: Exception) {}
}

fun dbCommit(): Boolean {
    return TransactionManager.currentOrNull()?.commit() != null
}

fun rollback(): Boolean {
    return TransactionManager.currentOrNull()?.rollback() != null
}

fun inDbTransaction(): Boolean = TransactionManager.currentOrNull() != null

fun <T> db(cache: Boolean = DbConnection.defaultCacheEnabled(), nestTransactions: Boolean = false, block: () -> T): T {
    val inTransaction = TransactionManager.currentOrNull() != null
    val run: (() -> T) -> T = if (inTransaction && !nestTransactions) { b -> b() } else { b -> transaction { b() } }

    val isOutermost = DbConnection.transactionStartedAt.get() == null
    if (isOutermost) {
        DbConnection.transactionStartedAt.set(DbConnection.clock.millis())
    }

    return try {
        run {
            if (cache) {
                TransactionCache { block() }
            } else {
                block()
            }
        }
    } finally {
        if (isOutermost) {
            DbConnection.transactionStartedAt.remove()
        }
    }
}


object DbConnection {
    var clock: Clock = Clock.systemUTC()

    internal val transactionStartedAt = ThreadLocal<Long>()

    /** Returns the timestamp when the current transaction started, or the current clock time if not in a transaction. */
    val transactionAt: Long
        get() = transactionStartedAt.get() ?: clock.millis()

    val jdbcDriver = Ack.string("JDBC_DRIVER", "org.postgresql.Driver", description = "JDBC driver class name.")

    val jdbcUrl = Ack.string("JDBC_URL", description = "JDBC connection URL for the application database.")

    val jdbcUser = Ack.string("JDBC_USER", "", description = "Database user name.")

    val jdbcPassword = Ack.secret("JDBC_PASSWORD", "", description = "Database password.")

    val useConnectionPool = Ack.boolean("POOL_JDBC_CONNECTIONS", false, description = "Whether to use a HikariCP connection pool.")

    val maximumPoolSize = Ack.int("JDBC_CONNECTION_MAX", 10, description = "Maximum size of the JDBC connection pool.")

    val minimumIdle = Ack.int("JDBC_CONNECTION_MIN_IDLE", maximumPoolSize, description = "Minimum number of idle connections in the pool.")

    val maxLifetimeSeconds = Ack.int("JDBC_CONNECTION_MAX_LIFETIME_SECONDS", 1800, description = "Maximum lifetime in seconds of a pooled connection.")

    val idleTimeoutSeconds = Ack.int("JDBC_CONNECTION_IDLE_TIMEOUT_SECONDS", 600, description = "Idle timeout in seconds before a pooled connection is retired.")

    val connectionTimeoutSeconds = Ack.int("JDBC_CONNECTION_TIMEOUT_SECONDS", 5, description = "Seconds to wait for a connection from the pool before failing.")

    // Logs a stack trace if a connection is held too long. This is your "smoke detector" for unclosed transactions.
    val leakDetectionThresholdSeconds = Ack.int("JDBC_CONNECTION_LEAK_DETECTION_THRESHOLD_SECONDS", 30, description = "Seconds a connection may be held before a leak is logged.")
    
    
    val defaultCacheEnabled = Ack.boolean("JDBC_DEFAULT_CACHE_ENABLED", true, description = "Whether the default per-transaction query cache is enabled.")
    

    private var closeableDataSource: Closeable? = null

    private var connectionProvider: ConnectionProvider? = null

    val isStarted: Boolean get() = connectionProvider != null

    fun isReachable(validationTimeoutSeconds: Int = VALIDATION_TIMEOUT_SECONDS): Boolean =
        runCatching {
            db(cache = false) { dbConnection().isValid(validationTimeoutSeconds) }
        }.getOrDefault(false)

    fun startup() {
        check(connectionProvider == null) { "DB is already started."}
        jdbcUrl.checkDefined()
        Class.forName(jdbcDriver())

        val connectionProvider: ConnectionProvider = if (useConnectionPool()) {
            val config = HikariConfig().apply {
                jdbcUrl = jdbcUrl()
                username = jdbcUser()
                password = jdbcPassword()
                driverClassName = jdbcDriver()

                maximumPoolSize = maximumPoolSize()
                minimumIdle = minimumIdle()

                connectionTimeout = connectionTimeoutSeconds() * 1000L
                maxLifetime = maxLifetimeSeconds() * 1000L
                idleTimeout = idleTimeoutSeconds() * 1000L

                leakDetectionThreshold = leakDetectionThresholdSeconds() * 1000L
            }

            val dataSource = HikariDataSource(config).also { closeableDataSource = it }
            object : ConnectionProvider {
                override fun getConnection(): Connection {
                    return dataSource.connection
                }
            }

        } else {
            object : ConnectionProvider {
                override fun getConnection(): Connection {
                    return DriverManager.getConnection(jdbcUrl(), jdbcUser(), jdbcPassword())
                }
            }
        }

        this.connectionProvider = connectionProvider

        Database.connect({ connectionProvider.getConnection() }, DatabaseConfig {
            explicitDialect = PostgreSQLDialect()
            defaultMaxAttempts = 1
            useNestedTransactions = true
        })
    }

    fun createConnection(): Connection {
        return connectionProvider?.getConnection()
            ?: throw IllegalStateException("DB has not been started.")
    }

    fun shutdown() {
        closeableDataSource?.close()
        closeableDataSource = null
        connectionProvider = null
    }
}

private fun interface ConnectionProvider {
    fun getConnection(): Connection
}
