package org.tekfive.keep.data

import kotlin.reflect.full.companionObjectInstance

/**
 * Base class for KEEP data objects using the existing Long primary-key strategy.
 * Use [UuidData] for UUIDv7 primary keys.
 */
abstract class Data : IdentifiedData<Long>(), HasLongId {
    /**
     * This concrete data class's companion [DataTable].
     * @throws IllegalStateException if the companion is not a DataTable for this class.
     */
    val table: DataTable<*>
        get() {
            val table = this::class.companionObjectInstance as? DataTable<*>
            check(table != null && table.dataClass == this::class) {
                "${this::class.qualifiedName} must define its table as a companion object " +
                    "extending DataTable<${this::class.simpleName}>"
            }
            return table
        }

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
