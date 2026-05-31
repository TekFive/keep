package org.tekfive.keep.data

import org.tekfive.kviash.routing.HasRouteParameter

interface HasId : HasRouteParameter {
    val id: Any

    override fun getParameter(): String {
        return id.toString()
    }
}