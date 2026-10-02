package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.tekfive.keep.schema.PostgresTriggerEvent
import org.tekfive.keep.schema.postgresObjects
import java.security.MessageDigest

/**
 * Deletes the referenced row after this row is deleted, using an AFTER DELETE row trigger.
 * Requires a single-column foreign key on a KEEP DataTable or UuidDataTable. Null values are
 * skipped; other references to the parent apply their normal foreign-key actions.
 *
 * Created through KEEP's schema installation and migration APIs. The existing foreign key's
 * onDelete action is preserved: [fkey] defaults to CASCADE, enabling deletion in both directions.
 * Returns this column for further chaining, preserving its property mapping and nullability.
 */
fun <T> Column<T>.cascadeDeleteParent(triggerName: String? = null): Column<T> = apply {
    require(table is DataTable<*> || table is UuidDataTable<*>) {
        "cascadeDeleteParent requires a KEEP DataTable or UuidDataTable"
    }
    val keepTable = table as TypedDataTuple<*, *>
    val tableName = table.tableName.substringAfterLast('.').removeSurrounding("\"")
    val resolvedName = triggerName ?: generatedDeleteParentName("${tableName}_${name}_delete_parent")
    val functionName = generatedDeleteParentName("${tableName}_${resolvedName}_fn")
    keepTable.columnPostgresObjects += table.postgresObjects {
        rowTrigger(resolvedName, functionName) {
            after(PostgresTriggerEvent.DELETE)
            onDelete { deleteReferencedRow(this@cascadeDeleteParent) }
        }
    }
}

/** Keep readable names where possible and disambiguate truncated UTF-8 names with a stable hash. */
private fun generatedDeleteParentName(name: String): String {
    if (name.toByteArray(Charsets.UTF_8).size <= 63) return name
    val suffix = "_" + MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
        .take(6).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    val prefix = StringBuilder()
    var bytes = 0
    for (codePoint in name.codePoints().toArray()) {
        val character = String(Character.toChars(codePoint))
        val size = character.toByteArray(Charsets.UTF_8).size
        if (bytes + size > 63 - suffix.length) break
        prefix.append(character)
        bytes += size
    }
    return "$prefix$suffix"
}
