package org.tekfive.keep.data

interface HasDescription {
    val description: String?

    val htmlDescription: String
        get() = Data.escapeHtml(description)

    val hasDescription: Boolean
        get() = !description.isNullOrEmpty()
}