package org.tekfive.keep.paged

import org.jetbrains.exposed.v1.core.SortOrder
import org.tekfive.ack.Ack
import org.tekfive.kviash.exchange.Exchange
import org.tekfive.kviash.http.HttpRequestParameters

data class PageRequest(
    val page: Int,
    val size: Int,
    val search: String?,
    val sort: List<SortField>,
) {
    data class SortField(val name: String, val direction: SortOrder)

    companion object {
        val minPageSizeAck = Ack.int("MIN_PAGE_SIZE", 10, description = "Minimum allowed page size for paged queries.")

        val defaultPageSizeAck = Ack.int("DEFAULT_PAGE_SIZE", 25, description = "Default page size when a request omits one.")

        val maxPageSizeAck = Ack.int("MAX_PAGE_SIZE", 100, description = "Maximum allowed page size for paged queries.")

        fun from(parameters: HttpRequestParameters, defaultSize: Int = defaultPageSizeAck()): PageRequest {
            val page = parameters.getInt("page") ?: 1

            val minPageSize = minPageSizeAck()
            val maxPageSize = maxPageSizeAck()

            val size = (parameters.getInt("size") ?: defaultSize).coerceIn(minPageSize, maxPageSize)
            val search = parameters["q"]
            val sort = parseSort(parameters["sort"])
            return PageRequest(page, size, search, sort)
        }

        private fun parseSort(param: String?): List<SortField> {
            if (param.isNullOrBlank()) return emptyList()
            return param.split(",").mapNotNull { segment ->
                val parts = segment.split(":")
                if (parts.isEmpty()) return@mapNotNull null
                val dir = if (parts.getOrNull(1) == "desc") SortOrder.DESC else SortOrder.ASC
                SortField(parts[0], dir)
            }
        }
    }
}
