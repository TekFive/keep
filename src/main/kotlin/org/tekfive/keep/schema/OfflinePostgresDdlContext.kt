@file:OptIn(org.jetbrains.exposed.v1.core.InternalApi::class)

package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.core.DatabaseApi
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.Version
import org.jetbrains.exposed.v1.core.statements.api.IdentifierManagerApi
import org.jetbrains.exposed.v1.core.transactions.TransactionManagerApi
import org.jetbrains.exposed.v1.core.transactions.withThreadLocalTransaction
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect

/**
 * Supplies only the deterministic dialect and identifier information Exposed's DDL value renderers
 * require. It has no connection API and cannot execute or inspect SQL.
 */
internal fun <T> withOfflinePostgresDdlContext(
    targetVersion: PostgresTargetVersion,
    block: () -> T,
): T {
    val dialect = PostgreSQLDialect()
    val database = OfflinePostgresDatabase(dialect, targetVersion)
    return withThreadLocalTransaction(OfflinePostgresTransaction(database), block)
}

private class OfflinePostgresTransaction(
    override val db: DatabaseApi,
) : Transaction() {
    override val transactionManager: TransactionManagerApi = OfflinePostgresTransactionManager()
    override val readOnly: Boolean = true
    override val outerTransaction: Transaction? = null
}

private class OfflinePostgresTransactionManager : TransactionManagerApi {
    override var defaultReadOnly: Boolean = true
    override var defaultMaxAttempts: Int = 1
    override var defaultMinRetryDelay: Long = 0
    override var defaultMaxRetryDelay: Long = 0
}

private class OfflinePostgresDatabase(
    override val dialect: PostgreSQLDialect,
    targetVersion: PostgresTargetVersion,
) : DatabaseApi(
    resolvedVendor = "postgresql",
    config = DatabaseConfig { explicitDialect = dialect },
) {
    override val url: String = "offline:postgresql"
    override val vendor: String = "postgresql"
    override val dialectMode: String = ""
    override val version: Version = Version(targetVersion.major, targetVersion.minor, 0)
    override val fullVersion: String = version.toString()
    override val supportsAlterTableWithAddColumn: Boolean = true
    override val supportsAlterTableWithDropColumn: Boolean = true
    override val supportsMultipleResultSets: Boolean = true
    override val supportsSelectForUpdate: Boolean = true
    override val identifierManager: IdentifierManagerApi = OfflinePostgresIdentifierManager()
}

private class OfflinePostgresIdentifierManager : IdentifierManagerApi() {
    override val quoteString: String = "\""
    override val isUpperCaseIdentifiers: Boolean = false
    override val isUpperCaseQuotedIdentifiers: Boolean = false
    override val isLowerCaseIdentifiers: Boolean = true
    override val isLowerCaseQuotedIdentifiers: Boolean = false
    override val supportsMixedIdentifiers: Boolean = false
    override val supportsMixedQuotedIdentifiers: Boolean = true
    override val extraNameCharacters: String = "$"
    override val oracleVersion: OracleVersion = OracleVersion.NonOracle
    override val maxColumnNameLength: Int = 63

    override fun dbKeywords(): List<String> = POSTGRES_KEYWORDS

    companion object {
        // PostgreSQL reserved words that are most likely to appear as application identifiers.
        private val POSTGRES_KEYWORDS = listOf(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric",
            "authorization", "binary", "both", "case", "cast", "check", "collate", "collation",
            "column", "concurrently", "constraint", "create", "cross", "current_catalog",
            "current_date", "current_role", "current_schema", "current_time", "current_timestamp",
            "current_user", "default", "deferrable", "desc", "distinct", "do", "else", "end",
            "except", "false", "fetch", "for", "foreign", "freeze", "from", "full", "grant",
            "group", "having", "ilike", "in", "initially", "inner", "intersect", "into", "is",
            "isnull", "join", "lateral", "leading", "left", "like", "limit", "localtime",
            "localtimestamp", "natural", "not", "notnull", "null", "offset", "on", "only", "or",
            "order", "outer", "overlaps", "placing", "primary", "references", "returning", "right",
            "select", "session_user", "similar", "some", "symmetric", "system_user", "table",
            "tablesample", "then", "to", "trailing", "true", "union", "unique", "user", "using",
            "variadic", "verbose", "when", "where", "window", "with",
        )
    }
}
