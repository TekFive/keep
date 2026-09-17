package org.tekfive.keep.data

import java.util.UUID

/**
 * Base class for KEEP data objects whose primary key is a UUIDv7.
 * Unsaved instances expose a stable temporary [id] while [idOrNull] remains null.
 */
abstract class UuidData : IdentifiedData<UUID>(), HasUuidId {
    private val temporaryId: UUID by lazy { UUID.randomUUID() }

    /** Database ID when linked; otherwise this instance's temporary UUID, also reused after unlinking. */
    override val id: UUID
        get() = super.idOrNull ?: temporaryId
}
