package org.tekfive.keep.interceptors

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.kviash.exchange.Exchange
import org.tekfive.kviash.exchange.ExchangeException
import org.tekfive.kviash.exchange.interceptors.PipelineInterceptor

object ExposedJdbcTransactionInterceptor : PipelineInterceptor {
    override fun intercept(exchange: Exchange, continuePipeline: (Exchange) -> Unit) {
        transaction {
            continuePipeline(exchange)
            // The pipeline converts action exceptions into error responses instead of
            // rethrowing, so they never unwind this transaction on their own. An
            // unexpected exception (an internal error) may have interrupted the action
            // mid-write — roll back so partial state never commits.
            //
            // Deliberate ExchangeExceptions (a 4xx ReturnErrorStatus, a redirect) still
            // commit: write-then-reject flows such as failed-login audit events and
            // account-lockout counters depend on their writes surviving the error
            // response. Controllers must therefore throw 4xx errors only *before*
            // performing writes.
            if (exchange.exceptions.any { it !is ExchangeException }) {
                rollback()
            }
        }
    }
}
