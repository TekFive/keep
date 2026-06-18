package org.tekfive.keep.data

interface HasLongId : HasId {
    override val id: Long
}

fun Collection<HasLongId>.toIds(): List<Long> {
    return map { it.id }
}

fun Collection<HasLongId>.toDistinctIds(): Set<Long> {
    return map { it.id }.toSet()
}