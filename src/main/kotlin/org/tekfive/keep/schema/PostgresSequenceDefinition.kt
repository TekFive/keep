package org.tekfive.keep.schema

/** Connection-free description of a PostgreSQL sequence used by a [KeepSchema]. */
data class PostgresSequenceDefinition(
    val name: String,
    val startWith: Long? = null,
    val incrementBy: Long? = null,
    val minValue: Long? = null,
    val maxValue: Long? = null,
    val cycle: Boolean? = null,
    val cache: Long? = null,
)
