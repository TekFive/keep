package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.json.jsonObject
import org.tekfive.jfk.JsonObject

/**
 * Abstract base table for recording [Data] change history. Stores a timestamp and the
 * dirty properties as a JSONB object. Extends [Table] directly — not a [DataTable].
 *
 * Subclass and add any additional columns (e.g. a foreign key to the source table):
 * ```
 * object UserChanges : DataChangeTable("user_changes") {
 *     val userId = long("user_id").references(UsersTable.id)
 * }
 * ```
 */
abstract class DataChangeTable(
    name: String,
    idSequenceName: String = DataTable.DeaultSequenceName,
) : Table(name) {
    val id: Column<Long> = long("id").autoIncrement(idSequenceName)
    val createdAt: Column<Long> = long("created_at")
    val changes: Column<JsonObject> = jsonObject("changes")

    override val primaryKey = PrimaryKey(id)
}
