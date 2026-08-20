package org.tekfive.keep.migration

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.data.InstantStorage
import org.tekfive.keep.data.column
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresViewDefinition
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val GENERATOR_SCHEMA = "keep_migration_generator_test"

private object GeneratorWidgets : Table("$GENERATOR_SCHEMA.widgets") {
    val id = long("id")
    val relaxed = text("relaxed").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object UuidGeneratorWidgets : Table("$GENERATOR_SCHEMA.widgets") {
    val id = javaUUID("id")
    val relaxed = text("relaxed").nullable()
    override val primaryKey = PrimaryKey(id)
}

private class GeneratorInstantModel(val occurredAt: Instant)

private object GeneratorBigintInstants : Table("$GENERATOR_SCHEMA.instant_events") {
    val occurredAt = column(GeneratorInstantModel::occurredAt)
}

private object GeneratorNativeInstants : Table("$GENERATOR_SCHEMA.instant_events") {
    val occurredAt = column(
        GeneratorInstantModel::occurredAt,
        storage = InstantStorage.TIMESTAMP_WITH_TIME_ZONE,
    )
}

private data class MigrationTestSchema(
    val targetSchemaName: String,
    override val tables: List<Table>,
    override val views: List<PostgresViewDefinition> = emptyList(),
    override val sequenceNames: List<String> = emptyList(),
) : KeepSchema(targetSchemaName)

class PostgresMigrationGeneratorTest {
    private lateinit var database: Database

    private val keepSchema = MigrationTestSchema(
        targetSchemaName = GENERATOR_SCHEMA,
        tables = listOf(GeneratorWidgets),
        views = listOf(
            PostgresViewDefinition(
                name = "widget_view",
                query = "SELECT id, relaxed FROM $GENERATOR_SCHEMA.widgets",
            )
        ),
        sequenceNames = listOf("widget_number_seq"),
    )

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) {
            exec("DROP SCHEMA IF EXISTS $GENERATOR_SCHEMA CASCADE")
            exec("CREATE SCHEMA $GENERATOR_SCHEMA")
        }
    }

    @AfterTest
    fun teardown() {
        transaction(database) {
            exec("DROP SCHEMA IF EXISTS $GENERATOR_SCHEMA CASCADE")
        }
    }

    @Test
    fun `fresh schema plan creates tables views and standalone sequences`() {
        val plan = PostgresMigrationGenerator.plan(database, keepSchema, nonDestructive = true)

        assertTrue(plan.suppressedStatements.isEmpty())
        assertTrue(plan.statements.any { it.startsWith("CREATE TABLE") && it.contains("widgets") })
        assertContains(
            plan.statements,
            "CREATE SEQUENCE \"$GENERATOR_SCHEMA\".\"widget_number_seq\"",
        )
        assertTrue(plan.statements.any { it.startsWith("CREATE VIEW") && it.contains("widget_view") })

        transaction(database) {
            plan.statements.forEach { exec(it) }
        }

        val secondPlan = PostgresMigrationGenerator.plan(database, keepSchema, nonDestructive = true)
        assertEquals(emptyList(), secondPlan.statements)
        assertEquals(emptyList(), secondPlan.suppressedStatements)
    }

    @Test
    fun `missing schema is created before its declared objects`() {
        transaction(database) {
            exec("DROP SCHEMA $GENERATOR_SCHEMA CASCADE")
        }

        val plan = PostgresMigrationGenerator.plan(database, keepSchema, nonDestructive = true)

        assertEquals("CREATE SCHEMA \"$GENERATOR_SCHEMA\"", plan.statements.first())
        transaction(database) {
            plan.statements.forEach { exec(it) }
        }
        val secondPlan = PostgresMigrationGenerator.plan(database, keepSchema, nonDestructive = true)
        assertTrue(secondPlan.isEmpty)
    }

    @Test
    fun `views are created in supplied dependency order`() {
        val viewSchema = keepSchema.copy(
            views = listOf(
                PostgresViewDefinition(
                    name = "base_widgets",
                    query = "SELECT id, relaxed FROM $GENERATOR_SCHEMA.widgets",
                ),
                PostgresViewDefinition(
                    name = "dependent_widgets",
                    query = "SELECT id FROM $GENERATOR_SCHEMA.base_widgets",
                ),
            )
        )

        val plan = PostgresMigrationGenerator.plan(database, viewSchema, nonDestructive = true)
        val viewStatements = plan.statements.filter { it.startsWith("CREATE VIEW") }
        assertTrue(viewStatements[0].contains("base_widgets"))
        assertTrue(viewStatements[1].contains("dependent_widgets"))

        transaction(database) {
            plan.statements.forEach { exec(it) }
        }
        assertTrue(PostgresMigrationGenerator.plan(database, viewSchema, true).isEmpty)
    }

    @Test
    fun `non-destructive plan keeps data objects but may relax NOT NULL`() {
        transaction(database) {
            exec(
                """
                CREATE TABLE $GENERATOR_SCHEMA.widgets (
                    id BIGINT PRIMARY KEY,
                    relaxed BIGINT NOT NULL,
                    obsolete TEXT
                )
                """.trimIndent()
            )
            exec("CREATE TABLE $GENERATOR_SCHEMA.extra_table (id BIGINT)")
            exec("CREATE VIEW $GENERATOR_SCHEMA.extra_view AS SELECT id FROM $GENERATOR_SCHEMA.extra_table")
            exec("CREATE SEQUENCE $GENERATOR_SCHEMA.extra_sequence")
        }

        val plan = PostgresMigrationGenerator.plan(database, keepSchema, nonDestructive = true)

        assertTrue(plan.statements.any { it.contains("DROP NOT NULL", ignoreCase = true) })
        assertFalse(plan.statements.any { it.contains("DROP COLUMN", ignoreCase = true) })
        assertFalse(plan.statements.any { it.startsWith("DROP TABLE", ignoreCase = true) })
        assertFalse(plan.statements.any { it.startsWith("DROP VIEW", ignoreCase = true) })
        assertFalse(plan.statements.any { it.startsWith("DROP SEQUENCE", ignoreCase = true) })

        val suppressedReasons = plan.suppressedStatements.map { it.reason }.toSet()
        assertContains(suppressedReasons, DestructivePostgresMigrationChange.DROP_COLUMN)
        assertTrue(
            DestructivePostgresMigrationChange.ALTER_COLUMN_TYPE in suppressedReasons,
            "Expected a suppressed type rewrite; statements=${plan.statements}, suppressed=${plan.suppressedStatements}",
        )
        assertContains(suppressedReasons, DestructivePostgresMigrationChange.DROP_TABLE)
        assertContains(suppressedReasons, DestructivePostgresMigrationChange.DROP_VIEW)
        assertContains(suppressedReasons, DestructivePostgresMigrationChange.DROP_SEQUENCE)
    }

    @Test
    fun `destructive plan includes removal of undeclared objects`() {
        transaction(database) {
            exec("CREATE TABLE $GENERATOR_SCHEMA.widgets (id BIGINT PRIMARY KEY, relaxed TEXT, obsolete TEXT)")
            exec("CREATE TABLE $GENERATOR_SCHEMA.extra_table (id BIGINT)")
            exec("CREATE SEQUENCE $GENERATOR_SCHEMA.extra_sequence")
        }

        val plan = PostgresMigrationGenerator.plan(database, keepSchema, nonDestructive = false)

        assertTrue(plan.suppressedStatements.isEmpty())
        assertTrue(plan.statements.any { it.contains("DROP COLUMN", ignoreCase = true) })
        assertTrue(plan.statements.any { it.startsWith("DROP TABLE", ignoreCase = true) })
        assertTrue(plan.statements.any { it.startsWith("DROP SEQUENCE", ignoreCase = true) })

        transaction(database) {
            plan.statements.forEach { exec(it) }
        }
        val secondPlan = PostgresMigrationGenerator.plan(database, keepSchema, nonDestructive = false)
        assertTrue(secondPlan.isEmpty, "Expected an empty plan after applying generated SQL: $secondPlan")
    }

    @Test
    fun `integer to UUID identity changes require an additive manual migration`() {
        transaction(database) {
            exec("CREATE TABLE $GENERATOR_SCHEMA.widgets (id BIGINT PRIMARY KEY, relaxed TEXT)")
        }
        val uuidSchema = keepSchema.copy(tables = listOf(UuidGeneratorWidgets), views = emptyList())

        val error = assertFailsWith<IllegalArgumentException> {
            PostgresMigrationGenerator.plan(database, uuidSchema, nonDestructive = false)
        }

        assertTrue(error.message.orEmpty().contains("manual additive migration"))
    }

    @Test
    fun `Instant storage migrations convert epoch milliseconds and native timestamps`() {
        val epochMillis = 1_700_000_000_123L
        transaction(database) {
            exec("CREATE TABLE $GENERATOR_SCHEMA.instant_events (occurred_at BIGINT NOT NULL)")
            exec("INSERT INTO $GENERATOR_SCHEMA.instant_events VALUES ($epochMillis)")
        }
        val nativeSchema = MigrationTestSchema(GENERATOR_SCHEMA, listOf(GeneratorNativeInstants))

        val safePlan = PostgresMigrationGenerator.plan(database, nativeSchema, nonDestructive = true)
        assertTrue(safePlan.statements.isEmpty())
        assertTrue(safePlan.suppressedStatements.single().sql.contains("to_timestamp"))
        assertEquals(
            DestructivePostgresMigrationChange.ALTER_COLUMN_TYPE,
            safePlan.suppressedStatements.single().reason,
        )

        val nativePlan = PostgresMigrationGenerator.plan(database, nativeSchema, nonDestructive = false)
        assertTrue(nativePlan.statements.single().contains("to_timestamp"))
        transaction(database) {
            nativePlan.statements.forEach { exec(it) }
            exec(
                "SELECT occurred_at = to_timestamp($epochMillis / 1000.0) " +
                    "FROM $GENERATOR_SCHEMA.instant_events"
            ) { result ->
                assertTrue(result.next())
                assertTrue(result.getBoolean(1))
            }
        }

        val bigintSchema = MigrationTestSchema(GENERATOR_SCHEMA, listOf(GeneratorBigintInstants))
        val bigintPlan = PostgresMigrationGenerator.plan(database, bigintSchema, nonDestructive = false)
        assertTrue(bigintPlan.statements.single().contains("extract(epoch"))
        transaction(database) {
            bigintPlan.statements.forEach { exec(it) }
            exec("SELECT occurred_at FROM $GENERATOR_SCHEMA.instant_events") { result ->
                assertTrue(result.next())
                assertEquals(epochMillis, result.getLong(1))
            }
        }
    }

    @Test
    fun `view shape changes require destructive mode`() {
        transaction(database) {
            exec("CREATE TABLE $GENERATOR_SCHEMA.widgets (id BIGINT PRIMARY KEY, relaxed TEXT)")
            exec(
                "CREATE VIEW $GENERATOR_SCHEMA.widget_view AS " +
                    "SELECT id, relaxed FROM $GENERATOR_SCHEMA.widgets"
            )
        }
        val changedSchema = keepSchema.copy(
            views = listOf(
                PostgresViewDefinition(
                    name = "widget_view",
                    query = "SELECT id FROM $GENERATOR_SCHEMA.widgets",
                )
            )
        )

        val safePlan = PostgresMigrationGenerator.plan(database, changedSchema, nonDestructive = true)
        assertFalse(safePlan.statements.any { it.contains("VIEW", ignoreCase = true) })
        assertEquals(
            listOf(
                DestructivePostgresMigrationChange.DROP_VIEW,
                DestructivePostgresMigrationChange.DROP_VIEW,
            ),
            safePlan.suppressedStatements.map { it.reason },
        )

        val destructivePlan = PostgresMigrationGenerator.plan(database, changedSchema, nonDestructive = false)
        val viewStatements = destructivePlan.statements.filter { it.contains("VIEW", ignoreCase = true) }
        assertTrue(viewStatements.first().startsWith("DROP VIEW"))
        assertTrue(viewStatements.last().startsWith("CREATE VIEW"))
    }

    @Test
    fun `materialized view replacement is suppressed in non-destructive mode`() {
        transaction(database) {
            exec("CREATE TABLE $GENERATOR_SCHEMA.widgets (id BIGINT PRIMARY KEY, relaxed TEXT)")
            exec(
                "CREATE MATERIALIZED VIEW $GENERATOR_SCHEMA.widget_totals AS " +
                    "SELECT count(*) AS total FROM $GENERATOR_SCHEMA.widgets"
            )
        }
        val materializedSchema = keepSchema.copy(
            views = listOf(
                PostgresViewDefinition(
                    name = "widget_totals",
                    query = "SELECT count(*) + 1 AS total FROM $GENERATOR_SCHEMA.widgets",
                    materialized = true,
                )
            )
        )

        val plan = PostgresMigrationGenerator.plan(database, materializedSchema, nonDestructive = true)

        assertFalse(plan.statements.any { it.contains("MATERIALIZED VIEW", ignoreCase = true) })
        assertEquals(2, plan.suppressedStatements.size)
        assertTrue(
            plan.suppressedStatements.all {
                it.reason == DestructivePostgresMigrationChange.DROP_MATERIALIZED_VIEW
            }
        )
    }
}
