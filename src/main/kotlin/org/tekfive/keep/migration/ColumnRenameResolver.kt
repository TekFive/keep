package org.tekfive.keep.migration

import org.jetbrains.exposed.v1.core.Table
import java.sql.Connection

internal fun resolveColumnRenames(connection: Connection, tables: List<Table>): List<String> =
    org.tekfive.keep.migration.dynamic.resolveColumnRenames(connection, tables).map { it.toSql() }
