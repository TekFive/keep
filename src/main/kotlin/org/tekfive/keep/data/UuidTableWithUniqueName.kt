package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import java.util.UUID

interface UuidTableWithUniqueName {
    val id: Column<UUID>

    val name: Column<String>

    fun isNameAlreadyTaken(
        name: String,
        candidateId: UUID? = null,
        additionalPredicate: (() -> Op<Boolean>)? = null,
    ): Boolean {
        var predicate = this.name eq name
        if (candidateId != null) predicate = predicate and (id neq candidateId)
        if (additionalPredicate != null) predicate = predicate and additionalPredicate()
        return (this as UuidDataTuple<*>).rowExists(predicate)
    }
}
