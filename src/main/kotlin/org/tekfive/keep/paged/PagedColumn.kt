package org.tekfive.keep.paged

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow

class PagedColumn(
    val name: String,
    val sortExpression: Expression<*>? = null,
    val serialize: (ResultRow) -> Any?,
)
