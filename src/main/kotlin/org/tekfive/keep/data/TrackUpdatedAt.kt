package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

/** Marks data whose `updated_at` column is maintained as epoch milliseconds in a [Long]. */
interface TrackUpdatedAt {
    var updatedAt: Long
}

/** Marks data whose `updated_at` column is maintained as an [Instant]. */
interface TrackUpdatedAtInstant {
    var updatedAt: Instant
}

internal fun Table.addTrackedUpdatedAtColumn(
    data: Any,
    columnsToUpdate: MutableSet<Column<*>>,
) {
    if (columnsToUpdate.any { it.name == "updated_at" }) return

    val trackerName = when (data) {
        is TrackUpdatedAt -> TrackUpdatedAt::class.simpleName
        is TrackUpdatedAtInstant -> TrackUpdatedAtInstant::class.simpleName
        else -> return
    }
    val updatedAtColumn = columns.firstOrNull { it.name == "updated_at" }
    check(updatedAtColumn != null) {
        "Data implements $trackerName but $tableName has no updated_at column."
    }

    val now = System.currentTimeMillis()
    when (data) {
        is TrackUpdatedAt -> data.updatedAt = now
        is TrackUpdatedAtInstant -> data.updatedAt = Instant.ofEpochMilli(now)
    }
    columnsToUpdate += updatedAtColumn
}
