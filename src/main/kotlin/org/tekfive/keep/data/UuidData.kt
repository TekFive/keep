package org.tekfive.keep.data

import java.util.UUID

/** Base class for KEEP data objects whose primary key is a UUIDv7. */
abstract class UuidData : IdentifiedData<UUID>(), HasUuidId {
    override val id: UUID
        get() = super.id
}
