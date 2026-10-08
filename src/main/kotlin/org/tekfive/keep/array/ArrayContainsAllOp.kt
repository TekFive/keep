package org.tekfive.keep.array

import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.ComparisonOp
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import java.util.UUID

/**
 * PostgreSQL UUID array containment (`column @> ?`), binding [values] as a UUID[] parameter.
 * Every supplied UUID must occur in the stored array; additional stored UUIDs are allowed.
 * Order and duplicate counts do not affect containment. Works with nullable and non-nullable
 * UUID list/set columns. An empty collection matches every non-null array, including an empty
 * array; SQL NULL does not match. The supplied collection is copied when building the predicate.
 */
infix fun ExpressionWithColumnType<out Collection<UUID>?>.containsAll(values: Collection<UUID>): Op<Boolean> =
    object : ComparisonOp(
        this@containsAll,
        QueryParameter(values.toList(), ArrayColumnType<UUID, List<UUID>>(UUIDColumnType())),
        "@>",
    ) {}
