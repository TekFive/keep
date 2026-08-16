package org.tekfive.keep.migration

import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PostgresMigrationSafetyTest {

    @Test
    fun `non-destructive classification permits removing NOT NULL`() {
        assertNull(destructiveChangeFor("ALTER TABLE widgets ALTER COLUMN label DROP NOT NULL"))
        assertNull(destructiveChangeFor("ALTER TABLE widgets DROP CONSTRAINT widgets_label_key"))
        assertNull(destructiveChangeFor("DROP INDEX widgets_label_idx"))
    }

    @Test
    fun `destructive classification covers data and object loss`() {
        assertEquals(
            DestructivePostgresMigrationChange.DROP_TABLE,
            destructiveChangeFor("DROP TABLE public.widgets"),
        )
        assertEquals(
            DestructivePostgresMigrationChange.DROP_COLUMN,
            destructiveChangeFor("ALTER TABLE widgets DROP COLUMN obsolete"),
        )
        assertEquals(
            DestructivePostgresMigrationChange.ALTER_COLUMN_TYPE,
            destructiveChangeFor("ALTER TABLE widgets ALTER COLUMN amount TYPE integer"),
        )
        assertEquals(
            DestructivePostgresMigrationChange.DELETE_DATA,
            destructiveChangeFor("DELETE FROM widgets WHERE id = 1"),
        )
        assertEquals(
            DestructivePostgresMigrationChange.TRUNCATE_DATA,
            destructiveChangeFor("TRUNCATE TABLE widgets"),
        )
    }

    @Test
    fun `compound alter statements are split before safety filtering`() {
        val statements = expandPostgresAlterTableStatement(
            "ALTER TABLE widgets " +
                "ALTER COLUMN amount TYPE numeric(12, 2), " +
                "ALTER COLUMN label DROP NOT NULL, " +
                "ALTER COLUMN note SET DEFAULT 'a,b'"
        )

        assertEquals(3, statements.size)
        assertEquals(
            DestructivePostgresMigrationChange.ALTER_COLUMN_TYPE,
            destructiveChangeFor(statements[0]),
        )
        assertNull(destructiveChangeFor(statements[1]))
        assertNull(destructiveChangeFor(statements[2]))
    }

    @Test
    fun `migration plan renders executable SQL without suppressed SQL`(@TempDir tempDir: Path) {
        val plan = PostgresMigrationPlan(
            statements = listOf("CREATE TABLE widgets (id BIGINT)"),
            suppressedStatements = listOf(
                SuppressedPostgresMigrationStatement(
                    "DROP TABLE legacy_widgets",
                    DestructivePostgresMigrationChange.DROP_TABLE,
                )
            ),
        )

        val sql = plan.toSql()
        assertContains(sql, "CREATE TABLE widgets (id BIGINT);")
        assertContains(sql, "1 destructive statement(s) were suppressed")
        kotlin.test.assertFalse(sql.contains("DROP TABLE legacy_widgets"))

        val file = tempDir.resolve("V1__widgets.sql")
        plan.writeTo(file)
        assertEquals(sql, Files.readString(file))
        assertFailsWith<FileAlreadyExistsException> { plan.writeTo(file) }
    }
}
