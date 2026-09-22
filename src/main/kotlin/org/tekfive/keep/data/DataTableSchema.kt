package org.tekfive.keep.data

import org.tekfive.keep.schema.PostgresTableObject

/** Desired PostgreSQL objects associated with a table, compared by dynamic migrations. */
interface DataTableSchema {
    /** Use a getter or lazy initializer for declarations that refer to other table objects. */
    val postgresObjects: List<PostgresTableObject>
        get() = emptyList()
}
