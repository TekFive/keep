package org.tekfive.keep.lock

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.tekfive.keep.data.UuidData
import org.tekfive.keep.data.UuidDataTable
import java.util.UUID

/** How a row lock holds its rows until the transaction ends. */
enum class RowLock(internal val option: ForUpdateOption) {
    /** No other transaction writes the row or locks it: for a change built on what the row holds. */
    EXCLUSIVE(ForUpdateOption.ForUpdate),

    /**
     * Other transactions may share the lock, but none writes the row: for a write that needs the row to stay as it
     * was checked, such as assigning a tag that could otherwise be archived at the same time.
     */
    SHARED(ForUpdateOption.PostgreSQL.ForShare()),
}

/**
 * The row [rowId] of this table, locked by [lock] until the transaction ends, or null when there is none. It is read
 * in the locking statement, so it holds what the last writer committed. Call inside a transaction.
 */
fun <A : UuidData> UuidDataTable<A>.findLocked(rowId: UUID, lock: RowLock = RowLock.EXCLUSIVE): A? {
    val row = selectAll().where(id eq rowId).forUpdate(lock.option).firstOrNull()
    if (row == null) {
        return null
    }
    return map(row)
}

/**
 * Locks the row [rowId] of this table by [lock] until the transaction ends, without reading it. Returns false when
 * there is none. Call inside a transaction.
 */
fun UuidDataTable<*>.lockRow(rowId: UUID, lock: RowLock = RowLock.EXCLUSIVE): Boolean {
    return select(id).where(id eq rowId).forUpdate(lock.option).any()
}

/**
 * Locks this whole table until the transaction ends, in `SHARE ROW EXCLUSIVE` mode: reads and row locks go on, but
 * other writers, and anyone else locking the table, wait. For writes that must be decided one at a time across the
 * table, where no one row serves as the lock. Call inside a transaction.
 */
fun Table.lockTable() {
    val transaction = TransactionManager.current()
    transaction.exec("LOCK TABLE ${transaction.identity(this)} IN SHARE ROW EXCLUSIVE MODE")
}
