package org.tekfive.keep.data

import java.util.UUID
import kotlin.reflect.full.companionObjectInstance

/**
 * Base class for KEEP data objects whose primary key is a UUIDv7.
 * Unsaved instances expose a stable temporary [id] while [idOrNull] remains null.
 */
abstract class UuidData : IdentifiedData<UUID>(), HasUuidId {
    /**
     * This concrete data class's companion [UuidDataTable].
     * @throws IllegalStateException if the companion is not a UuidDataTable for this class.
     */
    val table: UuidDataTable<*>
        get() {
            val table = this::class.companionObjectInstance as? UuidDataTable<*>
            check(table != null && table.dataClass == this::class) {
                "${this::class.qualifiedName} must define its table as a companion object " +
                    "extending UuidDataTable<${this::class.simpleName}>"
            }
            return table
        }

    private val temporaryId: UUID by lazy { UUID.randomUUID() }

    /** Database ID when linked; otherwise this instance's temporary UUID, also reused after unlinking. */
    override val id: UUID
        get() = super.idOrNull ?: temporaryId
}
