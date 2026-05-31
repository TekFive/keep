package org.tekfive.keep.lock

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private object LockSideEffectsTable : Table("lock_side_effects") {
    val id = integer("id")
    override val primaryKey = PrimaryKey(id)
}

class LocksTableTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        LocksTable.resetCreatedLocks()
        transaction {
            exec("CREATE EXTENSION IF NOT EXISTS \"citext\"")
            SchemaUtils.create(LocksTable, LockSideEffectsTable)
        }
    }

    @AfterTest
    fun teardown() {
        LocksTable.resetCreatedLocks()
        runCatching {
            transaction {
                SchemaUtils.drop(LockSideEffectsTable, LocksTable)
            }
        }
    }

    @Test
    fun `tryRunWithLock rolls back protected writes when runnable fails`() {
        assertFailsWith<IllegalStateException> {
            LocksTable.tryRunWithLock("rollback-test") {
                LockSideEffectsTable.insert {
                    it[id] = 1
                }
                error("boom")
            }
        }

        transaction {
            assertEquals(0L, LockSideEffectsTable.selectAll().count())
            assertEquals(0L, LocksTable.selectAll().count())
        }

        assertTrue(
            LocksTable.tryRunWithLock("rollback-test") {
                LockSideEffectsTable.insert {
                    it[id] = 2
                }
            }
        )

        transaction {
            assertEquals(1L, LockSideEffectsTable.selectAll().count())
            assertEquals(1L, LocksTable.selectAll().count())
        }
    }
}
