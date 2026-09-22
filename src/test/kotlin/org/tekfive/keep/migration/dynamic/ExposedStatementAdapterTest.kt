package org.tekfive.keep.migration.dynamic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ExposedStatementAdapterTest {
    @Test fun `quoted identifiers commas literals and multi-action alters remain structured`() {
        val statements = ExposedStatementAdapter.parse("""ALTER TABLE "some.schema"."some.table" RENAME COLUMN "old.name" TO "new.name""""
        )
        assertEquals(RenameColumn(QualifiedName("some.table", "some.schema"), "old.name", "new.name"), statements.single())
        val changes = ExposedStatementAdapter.parse("ALTER TABLE items ALTER COLUMN amount TYPE numeric(12,2), ALTER COLUMN note SET DEFAULT 'DROP COLUMN x, y', ALTER COLUMN amount DROP NOT NULL")
        assertEquals(3, changes.size)
        assertIs<AlterColumnType>(changes[0])
        assertEquals("'DROP COLUMN x, y'", assertIs<SetColumnDefault>(changes[1]).expression.text)
        assertIs<DropNotNull>(changes[2])
    }

    @Test fun `create table includes typed defaults and constraints`() {
        val table = assertIs<CreateTable>(ExposedStatementAdapter.parse("CREATE TABLE IF NOT EXISTS records (id BIGSERIAL PRIMARY KEY, label TEXT DEFAULT 'a,b' NOT NULL, owner BIGINT, CONSTRAINT owner_fk FOREIGN KEY (owner) REFERENCES owners(id) ON DELETE CASCADE, CONSTRAINT label_uq UNIQUE (label))").single())
        assertEquals(3, table.columns.size)
        assertEquals("'a,b'", table.columns[1].default?.text)
        assertEquals(ReferentialAction.CASCADE, assertIs<ConstraintDefinition.ForeignKey>(table.constraints[0]).onDelete)
        assertIs<ConstraintDefinition.Unique>(table.constraints[1])
    }

    @Test fun `unknown syntax and multiple statements are rejected`() {
        assertFailsWith<IllegalArgumentException> { ExposedStatementAdapter.parse("VACUUM items") }
        assertFailsWith<IllegalArgumentException> { ExposedStatementAdapter.parse("CREATE TABLE items (id INTEGER); DROP TABLE users") }
        assertFailsWith<IllegalArgumentException> { SqlExpression("1; DROP TABLE users") }
        assertFailsWith<IllegalArgumentException> { SqlExpression("1 -- hide suffix") }
    }

    @Test fun `partial expression index preserves predicate and options`() {
        val index = assertIs<CreateIndex>(ExposedStatementAdapter.parse("CREATE UNIQUE INDEX item_idx ON app.items USING btree (lower(label), id DESC) INCLUDE (name) WHERE label <> 'x,y'").single())
        assertEquals(2, index.definition.keys.size)
        assertEquals(listOf("name"), index.definition.include)
        assertEquals("label <> 'x,y'", index.definition.predicate?.text)
    }

    @Test fun `column comments and null defaults retain their structure`() {
        val comment = assertIs<SetComment>(ExposedStatementAdapter.parse("COMMENT ON COLUMN items.label IS 'Label, not a statement'").single())
        assertEquals(QualifiedName("items"), comment.name)
        assertEquals("label", comment.column)
        val qualified = assertIs<SetComment>(ExposedStatementAdapter.parse("COMMENT ON COLUMN app.items.label IS NULL").single())
        assertEquals(QualifiedName("items", "app"), qualified.name)
        assertEquals(null, qualified.comment)
        val table = assertIs<CreateTable>(ExposedStatementAdapter.parse("CREATE TABLE items (label TEXT DEFAULT NULL)").single())
        assertEquals(SqlExpression("NULL"), table.columns.single().default)
    }

    @Test fun `conditional constraint drops preserve both existence guards`() {
        val drop = assertIs<DropConstraint>(ExposedStatementAdapter.parse("ALTER TABLE IF EXISTS app.items DROP CONSTRAINT IF EXISTS items_uq").single())
        assertEquals(true, drop.tableIfExists)
        assertEquals(true, drop.ifExists)
    }
}
