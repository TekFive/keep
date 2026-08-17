package org.tekfive.keep.data

/**
 * Base class for KEEP data objects using the existing Long primary-key strategy.
 * Use [UuidData] for UUIDv7 primary keys.
 */
abstract class Data : IdentifiedData<Long>(), HasLongId {
    override val idOrNull: Long?
        get() = super.idOrNull

    override val id: Long
        get() = super.id

    override fun linkToDB(id: Long) {
        super.linkToDB(id)
    }

    companion object {
        @JvmStatic
        fun escapeHtml(input: String?): String = IdentifiedData.escapeHtml(input)
    }
}
