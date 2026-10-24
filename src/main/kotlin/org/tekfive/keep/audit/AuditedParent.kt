package org.tekfive.keep.audit

import org.tekfive.keep.data.UuidData
import java.util.UUID
import kotlin.reflect.KProperty1

/** A named child collection in a root table's history. */
class AuditedChildren<W : Any> internal constructor(
    val table: AuditedUuidTable<*, W>,
    val propertyId: UUID,
    val name: String,
)

/** Routes a child's snapshots to its parent's history. Nested child histories are rejected. */
class AuditedParent<D : UuidData, W : Any>(
    val children: AuditedChildren<W>,
    val parentId: KProperty1<in D, UUID>,
)
