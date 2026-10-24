package org.tekfive.keep.audit

import org.tekfive.keep.db.db
import org.tekfive.keep.db.dbConnection
import org.tekfive.keep.db.noDbCommit
import org.tekfive.keep.db.TransactionCache

/** A caught audit failure must not leave its row write in the enclosing transaction. */
internal fun <T> auditTransaction(block: () -> T): T = db {
    noDbCommit {
        // Read/modify/write hooks must not see an old cached row, including cached misses.
        TransactionCache.current?.store?.clear()
        val connection = dbConnection()
        val savepoint = connection.setSavepoint()
        try {
            block().also { connection.releaseSavepoint(savepoint) }
        } catch (failure: Throwable) {
            try {
                connection.rollback(savepoint)
                connection.releaseSavepoint(savepoint)
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
                try {
                    connection.rollback()
                } catch (fullRollbackFailure: Throwable) {
                    failure.addSuppressed(fullRollbackFailure)
                }
            }
            throw failure
        } finally {
            // Hooks may populate the cache before an eventual savepoint rollback.
            TransactionCache.current?.store?.clear()
        }
    }
}
