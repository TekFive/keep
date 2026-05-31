package org.tekfive.keep.utils

import java.sql.SQLException

val SQLException.isUniqueConstraint: Boolean
    get() =  sqlState in listOf("23505")