package org.tekfive.keep.audit

import org.jetbrains.exposed.v1.core.Table
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.data.ColumnGroup

/** The application supplies its writer identity, database columns, and JSON representation. */
interface AuditWriterCodec<W : Any> {
    fun columns(table: Table): ColumnGroup<W>
    fun encode(writer: W): JsonObject
    fun decode(json: JsonObject): W
}
