package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.TestDatabase
import org.tekfive.keep.data.dataEnum
import org.tekfive.keep.migration.PostgresMigrationGenerator
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val POLICY_SCHEMA = "keep_postgres_policy_test"

private enum class PolicyStatus(override val id: Int) : DataEnum {
    DRAFT(10),
    PUBLISHED(20),
    RETIRED(30),
}

private object PolicyVersions : Table("$POLICY_SCHEMA.pipeline_versions") {
    val id = long("id")
    val pipelineId = long("pipeline_id")
    val version = integer("version")
    val name = varchar("name", 120)
    val priority = integer("priority")
    val status = dataEnum<PolicyStatus>("status_id")
    val publishedAt = long("published_at").nullable()
    val retiredAt = long("retired_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        check("pipeline_versions_version_check") { version greater 0 }
        check("pipeline_versions_priority_check") { priority greaterEq 0 }
        check("pipeline_versions_publication_check") {
            ((status eq PolicyStatus.DRAFT) and publishedAt.isNull() and retiredAt.isNull()) or
                ((status eq PolicyStatus.PUBLISHED) and publishedAt.isNotNull() and retiredAt.isNull()) or
                ((status eq PolicyStatus.RETIRED) and publishedAt.isNotNull() and retiredAt.isNotNull())
        }
        uniqueIndex("pipeline_versions_one_draft_ix", pipelineId) { status eq PolicyStatus.DRAFT }
        uniqueIndex("pipeline_versions_one_published_ix", pipelineId) { status eq PolicyStatus.PUBLISHED }
    }
}

private val policyObjects = PolicyVersions.postgresObjects {
    uniqueConstraint(
        "pipeline_versions_pipeline_version_uq",
        PolicyVersions.pipelineId,
        PolicyVersions.version,
    )
    rowTrigger("pipeline_versions_guard_published", functionName = "guard_published_pipeline_version") {
        before(PostgresTriggerEvent.UPDATE, PostgresTriggerEvent.DELETE)
        onDelete {
            require(old[PolicyVersions.status] eq PolicyStatus.DRAFT) {
                "published pipeline versions cannot be deleted"
            }
        }
        onUpdate {
            transition(PolicyVersions.status) {
                from(PolicyStatus.DRAFT)
                    .allows(PolicyStatus.DRAFT, PolicyStatus.PUBLISHED)
                    .otherwise("a draft pipeline version must be published before retirement")
                from(PolicyStatus.PUBLISHED)
                    .allows(PolicyStatus.PUBLISHED, PolicyStatus.RETIRED)
                    .otherwise("a published pipeline version can only be retired")
                from(PolicyStatus.RETIRED)
                    .allows(PolicyStatus.RETIRED)
                    .otherwise("a retired pipeline version cannot be reopened")
            }
            immutableWhen(
                old[PolicyVersions.status] neq PolicyStatus.DRAFT,
                PolicyVersions.pipelineId,
                PolicyVersions.version,
                PolicyVersions.name,
                PolicyVersions.priority,
                PolicyVersions.publishedAt,
                message = "published pipeline versions are immutable",
            )
            immutableWhen(
                old[PolicyVersions.status] eq PolicyStatus.RETIRED,
                PolicyVersions.retiredAt,
                message = "retired pipeline versions are immutable",
            )
        }
    }
}

private object PolicySchema : KeepSchema(POLICY_SCHEMA) {
    override val tables = listOf(PolicyVersions)
    override val postgresObjects = policyObjects
}

class PostgresSchemaObjectsOfflineTest {
    @Test
    fun `typed PostgreSQL objects render constraints functions and triggers in dependency order`() {
        val statements = PostgresFreshInstallGenerator.plan(PolicySchema).statements
        val tableIndex = statements.indexOfFirst { it.startsWith("CREATE TABLE") }
        val uniqueConstraintIndex = statements.indexOfFirst { it.contains("pipeline_versions_pipeline_version_uq") }
        val functionIndex = statements.indexOfFirst { it.startsWith("CREATE OR REPLACE FUNCTION") }
        val triggerIndex = statements.indexOfFirst { it.startsWith("CREATE TRIGGER") }

        assertTrue(tableIndex < uniqueConstraintIndex)
        assertTrue(uniqueConstraintIndex < functionIndex)
        assertTrue(functionIndex < triggerIndex)

        val function = statements[functionIndex]
        assertTrue(function.contains("OLD.\"status_id\" <> 10"), function)
        assertTrue(function.contains("NEW.\"status_id\" IN (10, 20)"), function)
        assertTrue(function.contains("ROW(NEW.\"pipeline_id\""))
        assertTrue(function.contains("RAISE EXCEPTION USING MESSAGE"))

        val trigger = statements[triggerIndex]
        assertTrue(trigger.contains("BEFORE UPDATE OR DELETE"))
        assertTrue(trigger.contains("FOR EACH ROW"))
    }

    @Test
    fun `trigger rendering safely quotes messages and chooses a non-conflicting dollar quote`() {
        val trigger = PolicyVersions.postgresObjects {
            rowTrigger("pipeline_versions_escape_test") {
                before(PostgresTriggerEvent.INSERT)
                onInsert {
                    require(new[PolicyVersions.name] neq "") { "can't publish 100%" }
                    sql("PERFORM '${'$'}keep${'$'}';")
                }
            }
        }.single() as PostgresRowTriggerDefinition

        val sql = trigger.createFunctionStatement(PostgresRenderContext(POLICY_SCHEMA))

        assertTrue(sql.contains("AS ${'$'}keep_1${'$'}"))
        assertTrue(sql.contains("MESSAGE = 'can''t publish 100%'"))
    }
}

class PostgresSchemaObjectsIntegrationTest {
    private lateinit var database: Database

    @BeforeTest
    fun setup() {
        database = TestDatabase.connect()
        transaction(database) { exec("DROP SCHEMA IF EXISTS $POLICY_SCHEMA CASCADE") }
    }

    @AfterTest
    fun teardown() {
        transaction(database) { exec("DROP SCHEMA IF EXISTS $POLICY_SCHEMA CASCADE") }
    }

    @Test
    fun `migration creates typed PostgreSQL objects with a new table`() {
        transaction(database) { exec("CREATE SCHEMA $POLICY_SCHEMA") }

        val migration = PostgresMigrationGenerator.plan(database, PolicySchema, nonDestructive = true)
        val tableIndex = migration.statements.indexOfFirst { it.startsWith("CREATE TABLE") }
        val constraintIndex = migration.statements.indexOfFirst { it.contains("ADD CONSTRAINT") }
        val functionIndex = migration.statements.indexOfFirst { it.startsWith("CREATE OR REPLACE FUNCTION") }
        val triggerIndex = migration.statements.indexOfFirst { it.startsWith("CREATE TRIGGER") }

        assertTrue(tableIndex < constraintIndex)
        assertTrue(constraintIndex < functionIndex)
        assertTrue(functionIndex < triggerIndex)

        transaction(database) { migration.statements.forEach { exec(it) } }
        assertTrue(PostgresMigrationGenerator.plan(database, PolicySchema, true).isEmpty)
    }

    @Test
    fun `fresh install enforces typed constraints and trigger policies and migration is idempotent`() {
        val install = PostgresFreshInstallGenerator.plan(PolicySchema)
        transaction(database) { install.statements.forEach { exec(it) } }

        transaction(database) {
            exec(
                "INSERT INTO $POLICY_SCHEMA.pipeline_versions " +
                    "(id, pipeline_id, version, name, priority, status_id) " +
                    "VALUES (1, 100, 1, 'draft', 0, ${PolicyStatus.DRAFT.id})"
            )
        }

        assertFailsWith<SQLException> {
            transaction(database) {
                exec(
                    "UPDATE $POLICY_SCHEMA.pipeline_versions " +
                        "SET status_id = ${PolicyStatus.RETIRED.id}, published_at = 1, retired_at = 2 WHERE id = 1"
                )
            }
        }

        transaction(database) {
            exec(
                "UPDATE $POLICY_SCHEMA.pipeline_versions " +
                    "SET status_id = ${PolicyStatus.PUBLISHED.id}, published_at = 1 WHERE id = 1"
            )
        }

        assertFailsWith<SQLException> {
            transaction(database) {
                exec("UPDATE $POLICY_SCHEMA.pipeline_versions SET name = 'changed' WHERE id = 1")
            }
        }
        assertFailsWith<SQLException> {
            transaction(database) {
                exec("DELETE FROM $POLICY_SCHEMA.pipeline_versions WHERE id = 1")
            }
        }

        val merge = PostgresMigrationGenerator.plan(database, PolicySchema, nonDestructive = false)
        assertEquals(emptyList(), merge.statements, "Typed PostgreSQL objects were not idempotent: $merge")
    }

    @Test
    fun `migration replaces changed constraints trigger functions and trigger definitions`() {
        val install = PostgresFreshInstallGenerator.plan(PolicySchema)
        transaction(database) { install.statements.forEach { exec(it) } }

        val changedObjects = PolicyVersions.postgresObjects {
            uniqueConstraint(
                "pipeline_versions_pipeline_version_uq",
                PolicyVersions.pipelineId,
                PolicyVersions.name,
            )
            rowTrigger("pipeline_versions_guard_published", functionName = "guard_published_pipeline_version") {
                after(PostgresTriggerEvent.UPDATE, PostgresTriggerEvent.DELETE)
                onDelete {
                    require(old[PolicyVersions.status] eq PolicyStatus.DRAFT) {
                        "only drafts may be deleted"
                    }
                }
                onUpdate {
                    transition(PolicyVersions.status) {
                        from(PolicyStatus.DRAFT)
                            .allows(PolicyStatus.DRAFT, PolicyStatus.PUBLISHED)
                            .otherwise("draft transition rejected")
                        from(PolicyStatus.PUBLISHED)
                            .allows(PolicyStatus.PUBLISHED, PolicyStatus.RETIRED)
                            .otherwise("published transition rejected")
                        from(PolicyStatus.RETIRED)
                            .allows(PolicyStatus.RETIRED)
                            .otherwise("retired transition rejected")
                    }
                }
            }
        }
        val changedSchema = object : KeepSchema(POLICY_SCHEMA) {
            override val tables = listOf(PolicyVersions)
            override val postgresObjects = changedObjects
        }

        val migration = PostgresMigrationGenerator.plan(database, changedSchema, nonDestructive = true)
        assertTrue(migration.statements.any { it.contains("DROP CONSTRAINT") })
        assertTrue(migration.statements.any { it.contains("ADD CONSTRAINT") })
        assertTrue(migration.statements.any { it.startsWith("CREATE OR REPLACE FUNCTION") })
        assertTrue(migration.statements.any { it.startsWith("DROP TRIGGER") })
        assertTrue(migration.statements.any { it.startsWith("CREATE TRIGGER") && it.contains(" AFTER ") })

        transaction(database) { migration.statements.forEach { exec(it) } }

        val second = PostgresMigrationGenerator.plan(database, changedSchema, nonDestructive = true)
        assertTrue(second.isEmpty, "Changed PostgreSQL objects were not idempotent: $second")
    }
}
