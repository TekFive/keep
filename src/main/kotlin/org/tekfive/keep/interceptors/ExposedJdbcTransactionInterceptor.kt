package org.tekfive.keep.interceptors

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.kviash.exchange.Exchange
import org.tekfive.kviash.exchange.interceptors.PipelineInterceptor

object ExposedJdbcTransactionInterceptor : PipelineInterceptor {
    override fun intercept(exchange: Exchange, continuePipeline: (Exchange) -> Unit) {
        transaction {
            continuePipeline(exchange)
        }
    }
}