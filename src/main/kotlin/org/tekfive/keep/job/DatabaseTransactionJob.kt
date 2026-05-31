package org.tekfive.keep.job

import org.tekfive.keep.db.db
import org.tekfive.keep.db.dbConnection
import java.sql.Connection

abstract class DatabaseTransactionJob : Job {

    final override fun execute(context: JobContext): JobResult {
        return db {
            try {
                execute(context, dbConnection())
            } catch (e: JobResult) {
                e
            }
        }
    }

    abstract fun execute(context: JobContext, connection: Connection): JobResult
}
