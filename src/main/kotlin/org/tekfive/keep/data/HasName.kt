package org.tekfive.keep.data

interface HasName {
    val name: String

    val htmlName: String
        get() = Data.escapeHtml(name)
}