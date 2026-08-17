package org.tekfive.keep.data

import java.util.UUID

interface HasUuidId : HasId {
    override val id: UUID
}

fun Collection<HasUuidId>.toUuidIds(): List<UUID> = map { it.id }

fun Collection<HasUuidId>.toDistinctUuidIds(): Set<UUID> = mapTo(mutableSetOf()) { it.id }
