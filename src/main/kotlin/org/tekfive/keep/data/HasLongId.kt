package org.tekfive.keep.data

interface HasLongId : HasId {
    override val id: Long
}