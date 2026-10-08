package org.tekfive.keep.array

import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.ComparisonOp
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.tekfive.keep.data.DataEnum
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

/**
 * Returns true when this INTEGER[] enum column contains every supplied [DataEnum.id].
 * Binds a copied INTEGER[] parameter; additional stored values, order and duplicate counts
 * do not affect containment. Supports nullable/non-nullable lists and sets. Empty input matches
 * every non-null array, including an empty array; SQL NULL never matches.
 * Encrypted enum columns are not supported.
 */
@JvmName("containsAllDataEnum")
infix fun <E> ExpressionWithColumnType<out Collection<E>?>.containsAll(values: Collection<E>): Op<Boolean>
    where E : Enum<E>, E : DataEnum =
    object : ComparisonOp(
        this@containsAll,
        QueryParameter(values.map { it.id }, ArrayColumnType<Int, List<Int>>(IntegerColumnType())),
        "@>",
    ) {}
