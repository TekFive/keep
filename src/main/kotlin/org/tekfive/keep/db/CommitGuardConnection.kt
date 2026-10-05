package org.tekfive.keep.db

import java.sql.Connection
import java.util.IdentityHashMap

internal class DbCommitNotAllowedException : IllegalStateException("Database commits are not allowed inside noDbCommit.")

/** Checks at invocation time so references obtained before a noDbCommit scope are guarded too. */
internal class CommitGuardConnection private constructor(private val delegate: Connection) : Connection by delegate {
    override fun commit() {
        checkCommitAllowed(delegate)
        delegate.commit()
    }

    override fun setAutoCommit(autoCommit: Boolean) {
        // Enabling auto-commit commits outstanding work even without calling commit().
        if (autoCommit) checkCommitAllowed(delegate)
        delegate.autoCommit = autoCommit
    }

    override fun <T> unwrap(iface: Class<T>): T =
        if (iface.isInstance(this)) iface.cast(this) else delegate.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this) || delegate.isWrapperFor(iface)

    companion object {
        private val scopes = IdentityHashMap<Connection, Int>()

        fun wrap(connection: Connection): Connection =
            if (connection is CommitGuardConnection) connection else CommitGuardConnection(connection)

        private fun original(connection: Connection): Connection =
            if (connection is CommitGuardConnection) connection.delegate else connection

        fun sameConnection(first: Connection, second: Connection): Boolean = original(first) === original(second)

        fun enter(connection: Connection) = synchronized(scopes) {
            val key = original(connection)
            scopes[key] = (scopes[key] ?: 0) + 1
        }

        fun leave(connection: Connection) = synchronized(scopes) {
            val key = original(connection)
            val depth = checkNotNull(scopes[key])
            if (depth == 1) scopes.remove(key) else scopes[key] = depth - 1
        }

        fun checkCommitAllowed(connection: Connection) = synchronized(scopes) {
            if (scopes.containsKey(original(connection))) throw DbCommitNotAllowedException()
        }
    }
}
