package org.tekfive.keep.paged

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.JsonObjectBuilder
import org.tekfive.jfk.json
import org.tekfive.jfk.jsonArray

data class PagedResult<D>(
    val data: List<D>,
    val total: Int,
    val page: Int,
    val size: Int,
) {
    fun toJson(serialize: JsonObjectBuilder.(D) -> Unit): JsonObject {
        return json {
            "data" set jsonArray { data.forEach { addObject { serialize(it) } } }
            "total" set total
            "page" set page
            "size" set size
        }
    }
}
