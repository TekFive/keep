package org.tekfive.keep.data

interface HasActiveStatus {
    val active: Boolean

    val inactive: Boolean
        get() = !active

    val activePurpose: String?
        get() = null
}