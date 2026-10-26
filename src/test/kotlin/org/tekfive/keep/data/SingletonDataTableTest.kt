package org.tekfive.keep.data

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.migration.dynamic.PostgresMigrationGenerator
import org.tekfive.keep.schema.AppSchema
import org.tekfive.keep.schema.PostgresFreshInstallGenerator
import org.tekfive.keep.schema.postgresObjects
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

private const val SINGLETON_SCHEMA = "keep_singleton_test"

class SingletonSettings(val label: String, var enabled: Boolean) : Data() {
    companion object : SingletonDataTable<SingletonSettings>("$SINGLETON_SCHEMA.settings", "settings_ids") {
        val label = text("label")
        val enabled = bool("enabled")
        override val postgresObjects = postgresObjects { index("settings_label_idx", label) }
    }
}

private object SingletonSchema : AppSchema(SINGLETON_SCHEMA) {
    override val tables = listOf(SingletonSettings)
    override val sequences = listOf("settings_ids")
}

class SingletonDataTableTest {
    private lateinit var database: Database

    @BeforeTest fun setup() {
        database = TestDatabase.connect()
        transaction(database) {
            exec("DROP SCHEMA IF EXISTS $SINGLETON_SCHEMA CASCADE")
            PostgresFreshInstallGenerator.plan(SingletonSchema).statements.forEach { exec(it) }
        }
    }

    @AfterTest fun cleanup() {
        transaction(database) { exec("DROP SCHEMA IF EXISTS $SINGLETON_SCHEMA CASCADE") }
    }

    private fun <T> inSchema(block: () -> T): T = transaction(database) {
        maxAttempts = 1
        exec("SET LOCAL search_path TO $SINGLETON_SCHEMA, public")
        block()
    }

    private fun execute(sql: String) = transaction(database) { maxAttempts = 1; exec(sql) }

    @Test fun `empty singleton lookup and normal CRUD preserve mapping and change tracking`() {
        inSchema {
            assertNull(SingletonSettings.find())
            assertFailsWith<NoSuchElementException> { SingletonSettings.get() }
            val settings = SingletonSettings.create(SingletonSettings("production", true))
            assertSame(SingletonSettings, settings.table)
            assertTrue(settings.linkedToDb)
            assertFalse(settings.isDirty)
            assertEquals(settings.id, SingletonSettings.get().id)
            settings.enabled = false
            SingletonSettings.update(settings)
            assertFalse(settings.isDirty)
            assertFalse(SingletonSettings.get().enabled)
            SingletonSettings.delete(settings)
            assertNull(SingletonSettings.find())
            assertFalse(settings.linkedToDb)
            SingletonSettings.save(SingletonSettings("replacement", true))
            assertEquals("replacement", SingletonSettings.get().label)
        }
    }

    @Test fun `second create is rejected without replacing the original`() {
        inSchema { SingletonSettings.create(SingletonSettings("first", true)) }
        val error = assertFailsWith<SQLException> {
            inSchema { SingletonSettings.create(SingletonSettings("second", false)) }
        }
        assertEquals("23505", error.sqlState)
        inSchema { assertEquals("first", SingletonSettings.get().label) }
    }

    @Test fun `direct SQL cannot bypass the limit by using a different ID`() {
        execute("INSERT INTO $SINGLETON_SCHEMA.settings (id, label, enabled) VALUES (10, 'first', true)")
        val error = assertFailsWith<SQLException> {
            execute("INSERT INTO $SINGLETON_SCHEMA.settings (id, label, enabled) VALUES (20, 'second', false)")
        }
        assertEquals("23505", error.sqlState)
        inSchema { assertEquals(10L, SingletonSettings.get().id) }
    }

    @Test fun `multirow inserts cannot create multiple singleton rows`() {
        assertFailsWith<SQLException> {
            execute("INSERT INTO $SINGLETON_SCHEMA.settings (id, label, enabled) VALUES (10, 'first', true), (20, 'second', false)")
        }
        inSchema { assertNull(SingletonSettings.find()) }
    }

    @Test fun `getOrCreate returns existing data without evaluating its factory`() {
        val id = inSchema { SingletonSettings.getOrCreate { SingletonSettings("initial", true) }.id }
        inSchema {
            val existing = SingletonSettings.getOrCreate { error("Factory must not run when data exists") }
            assertEquals(id, existing.id)
            assertFalse(existing.isDirty)
            assertEquals("initial", existing.label)
        }
    }

    @Test fun `concurrent initializers return the same persisted row`() {
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { number ->
                executor.submit(Callable {
                    inSchema {
                        SingletonSettings.getOrCreate {
                            barrier.await(5, TimeUnit.SECONDS)
                            SingletonSettings("initializer-$number", true)
                        }
                    }
                })
            }
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(results[0].id, results[1].id)
            assertEquals(results[0].label, results[1].label)
            assertTrue(results.all { it.linkedToDb && !it.isDirty })
            inSchema { assertEquals(1L, SingletonSettings.selectAll().count()) }
        } finally { executor.shutdownNow() }
    }

    @Test fun `concurrent ordinary creates allow exactly one insert`() {
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { number -> executor.submit(Callable {
                runCatching {
                    inSchema {
                        barrier.await(5, TimeUnit.SECONDS)
                        SingletonSettings.create(SingletonSettings("writer-$number", true))
                    }
                }
            }) }
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            val error = assertIs<SQLException>(results.single { it.isFailure }.exceptionOrNull())
            assertEquals("23505", error.sqlState)
            inSchema { assertEquals(1L, SingletonSettings.selectAll().count()) }
        } finally { executor.shutdownNow() }
    }

    @Test fun `singleton index survives custom schema hooks and migration planning`() {
        val plan = PostgresMigrationGenerator.plan(database, SingletonSchema, nonDestructive = true)
        assertTrue(plan.isEmpty, plan.toString())
        execute("DROP INDEX $SINGLETON_SCHEMA.settings_singleton_uq")
        val repair = PostgresMigrationGenerator.plan(database, SingletonSchema, nonDestructive = true)
        assertTrue(repair.sqlStatements.any { "CREATE UNIQUE INDEX" in it && "settings_singleton_uq" in it })
        repair.execute(database)
        assertTrue(PostgresMigrationGenerator.plan(database, SingletonSchema, nonDestructive = true).isEmpty)
        inSchema { SingletonSettings.create(SingletonSettings("first", true)) }
        assertFailsWith<SQLException> { inSchema { SingletonSettings.create(SingletonSettings("second", false)) } }
    }

    @Test fun `SchemaUtils and AppSchema installations both enforce singleton storage`() {
        for (useAppSchema in listOf(false, true)) {
            inSchema { SchemaUtils.drop(SingletonSettings) }
            inSchema {
                if (useAppSchema) {
                    SingletonSchema.create()
                } else {
                    // Like any DataTable, the application's named ID sequence must exist.
                    TransactionManager.current().exec("CREATE SEQUENCE IF NOT EXISTS settings_ids")
                    SchemaUtils.create(SingletonSettings)
                }
            }
            // SchemaUtils may commit DDL, ending SET LOCAL search_path; use a fresh transaction.
            inSchema { SingletonSettings.create(SingletonSettings("first", true)) }
            assertFailsWith<SQLException> { inSchema { SingletonSettings.create(SingletonSettings("second", false)) } }
        }
    }
}
