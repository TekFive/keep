package org.tekfive.keep.migration.dynamic

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
    private val widgets = QualifiedName("widgets", "public")

    @Test
    fun `non-destructive classification permits removing NOT NULL`() {
        assertNull(DropNotNull(widgets, "label").destructiveChange)
        assertNull(DropConstraint(widgets, "widgets_label_key").destructiveChange)
        assertNull(DropIndex(QualifiedName("widgets_label_idx")).destructiveChange)
    }

    @Test
    fun `destructive classification covers data and object loss`() {
        assertEquals(
            DestructivePostgresMigrationChange.DROP_TABLE,
            DropTable(widgets).destructiveChange,
        )
        assertEquals(
            DestructivePostgresMigrationChange.DROP_COLUMN,
            DropColumn(widgets, "obsolete").destructiveChange,
        )
        assertEquals(
            DestructivePostgresMigrationChange.ALTER_COLUMN_TYPE,
            AlterColumnType(widgets, "amount", PostgresType("integer")).destructiveChange,
        )
        assertEquals(
            DestructivePostgresMigrationChange.DROP_SCHEMA,
            DropSchema("legacy").destructiveChange,
        )
        assertEquals(
            DestructivePostgresMigrationChange.UPDATE_DATA,
            SetGeneratedExpression(widgets, "amount", SqlExpression("price * quantity")).destructiveChange,
        )
    }

    @Test
    fun `compound alter statements are split before safety filtering`() {
        val statements = ExposedStatementAdapter.parse(
            "ALTER TABLE widgets " +
                "ALTER COLUMN amount TYPE numeric(12, 2), " +
                "ALTER COLUMN label DROP NOT NULL, " +
                "ALTER COLUMN note SET DEFAULT 'a,b'"
        )

        assertEquals(3, statements.size)
        assertEquals(
            DestructivePostgresMigrationChange.ALTER_COLUMN_TYPE,
            statements[0].destructiveChange,
        )
        assertNull(statements[1].destructiveChange)
        assertNull(statements[2].destructiveChange)
    }

    @Test
    fun `migration plan renders executable SQL without suppressed SQL`(@TempDir tempDir: Path) {
        val plan = PostgresMigrationPlan(
            statements = listOf(CreateTable(widgets, listOf(ColumnDefinition("id", PostgresType("BIGINT"))))),
            suppressedStatements = listOf(
                SuppressedPostgresMigrationStatement(
                    DropTable(QualifiedName("legacy_widgets")),
                    DestructivePostgresMigrationChange.DROP_TABLE,
                )
            ),
        )

        val sql = plan.toSql()
        assertContains(sql, "CREATE TABLE \"public\".\"widgets\" (\"id\" BIGINT);")
        assertContains(sql, "1 destructive statement(s) were suppressed")
        kotlin.test.assertFalse(sql.contains("legacy_widgets"))

        val file = tempDir.resolve("V1__widgets.sql")
        plan.writeTo(file)
        assertEquals(sql, Files.readString(file))
        assertFailsWith<FileAlreadyExistsException> { plan.writeTo(file) }
    }
}
