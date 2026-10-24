package org.tekfive.keep.audit

import org.tekfive.keep.data.UuidData
import java.util.UUID

/**
 * Audits individual values stored together in one mutable property, such as a JSON document.
 * Keep calls [loaded] after mapping and [saved] after writing. Implementations must retain an independent
 * baseline on the record and compare against that baseline. Merges replace the caller's value with the
 * current stored value plus only the caller's changes.
 * Callbacks run synchronously; they must not commit or roll back the transaction.
 */
interface AuditValueContributor<D : UuidData> {
    fun initialIds(data: D): Set<UUID>
    fun changedIds(data: D): Set<UUID>
    /** Replace the contributed value on [data], using [stored] as the base and applying only [changedIds]. */
    fun merge(data: D, stored: D, changedIds: Set<UUID>)
    fun loaded(data: D) {}
    fun saved(data: D) = loaded(data)
    fun propertyName(propertyId: UUID): String? = null
}
