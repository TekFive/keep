package org.tekfive.keep.lock

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.name
import org.tekfive.keep.data.uniqueIndexWithStandardName
import org.tekfive.keep.db.db
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

object LocksTable : DataTable<Lock>("locks") {
    private val createdLocks = ConcurrentHashMap.newKeySet<String>()

    val lockId = name().uniqueIndexWithStandardName()

    val lastLockAt = long("last_lock_at").nullable()

    fun resetCreatedLocks() {
        createdLocks.clear()
    }

    fun tryRunWithLock(kClass: KClass<*>, minSecondsSinceLastLock: Long? = null, maxSecondsToWaitOnLock: Int? = null, runnable:() -> Unit): Boolean {
        return tryRunWithLock(kClass.qualifiedName ?: kClass.java.name, minSecondsSinceLastLock, maxSecondsToWaitOnLock, runnable)
    }

    fun tryRunWithLock(lockId: String, minSecondsSinceLastLock: Long? = null, maxSecondsToWaitOnLock: Int? = null, runnable:() -> Unit): Boolean {
        return db {
            val connection = TransactionManager.current().connection.connection as Connection
            val markedCreated = createdLocks.add(lockId)
            try {
                if (markedCreated) {
                    if (!rowExists(this@LocksTable.lockId eq lockId)) {
                        create(Lock(lockId, null))
                    }
                }

                if (minSecondsSinceLastLock != null) {
                    val lastLockAt: Long? = connection.prepareStatement("SELECT * FROM $tableName WHERE ${this@LocksTable.lockId.name} = ? FOR UPDATE SKIP LOCKED").use { statement ->
                        statement.setString(1, lockId)
                        statement.executeQuery().use { set ->
                            if (set.next()) {
                                set.getLong(lastLockAt.name).let { if (set.wasNull()) null else it }
                            } else {
                                return@db false
                            }
                        }
                    }

                    val secondsSinceLastLock = lastLockAt?.let { ((System.currentTimeMillis() - it) / 1000L).toInt() }
                    if (secondsSinceLastLock != null && secondsSinceLastLock <= minSecondsSinceLastLock) {
                        return@db false
                    }
                }

                connection.prepareStatement("SELECT * FROM $tableName WHERE ${this@LocksTable.lockId.name} = ? FOR UPDATE", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE).use { statement ->
                    if (maxSecondsToWaitOnLock != null && maxSecondsToWaitOnLock > 0) {
                        statement.queryTimeout = maxSecondsToWaitOnLock
                    }
                    statement.setString(1, lockId)
                    try {
                        statement.executeQuery().use { set ->
                            if (!set.next()) {
                                return@db false
                            }
                            if (minSecondsSinceLastLock != null) {
                                val lastLockAt: Long? = set.getLong(lastLockAt.name).let { if (set.wasNull()) null else it }
                                val secondsSinceLastLock = lastLockAt?.let { ((System.currentTimeMillis() - it) / 1000L).toInt() }
                                if (secondsSinceLastLock != null && secondsSinceLastLock <= minSecondsSinceLastLock) {
                                    return@db false
                                }
                            }

                            set.updateLong(lastLockAt.name, System.currentTimeMillis())
                            set.updateRow()
                            runnable()

                            true
                        }
                    } catch (e: SQLTimeoutException) {
                        false
                    }
                }
            } catch (t: Throwable) {
                if (markedCreated) {
                    createdLocks.remove(lockId)
                }
                throw t
            }
        }
    }
}
