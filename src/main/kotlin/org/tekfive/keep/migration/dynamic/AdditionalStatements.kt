package org.tekfive.keep.migration.dynamic

data class DropSchema(val name: String, val ifExists: Boolean = false, val behavior: DropBehavior = DropBehavior.RESTRICT) : PostgresMigrationStatement {
    override val destructiveChange = DestructivePostgresMigrationChange.DROP_SCHEMA
    override fun toSql() = "DROP SCHEMA ${if (ifExists) "IF EXISTS " else ""}${quoteIdentifier(name)} $behavior"
}
data class CreateExtension(val name: String, val schema: String? = null, val version: String? = null, val ifNotExists: Boolean = true) : PostgresMigrationStatement {
    override fun toSql() = "CREATE EXTENSION ${if (ifNotExists) "IF NOT EXISTS " else ""}${quoteIdentifier(name)}" +
        (schema?.let { " SCHEMA ${quoteIdentifier(it)}" } ?: "") + (version?.let { " VERSION ${literal(it)}" } ?: "")
}
data class UpdateExtension(val name: String, val version: String? = null) : PostgresMigrationStatement {
    override fun toSql() = "ALTER EXTENSION ${quoteIdentifier(name)} UPDATE" + (version?.let { " TO ${literal(it)}" } ?: "")
}
data class DropExtension(val name: String, val ifExists: Boolean = false, val behavior: DropBehavior = DropBehavior.RESTRICT) : PostgresMigrationStatement {
    override val destructiveChange = DestructivePostgresMigrationChange.DROP_EXTENSION
    override fun toSql() = "DROP EXTENSION ${if (ifExists) "IF EXISTS " else ""}${quoteIdentifier(name)} $behavior"
}
data class CreateEnumType(val name: QualifiedName, val values: List<String>) : PostgresMigrationStatement {
    init { require(values.distinct().size == values.size) }
    override fun toSql() = "CREATE TYPE ${name.toSql()} AS ENUM (${values.joinToString(transform = ::literal)})"
}
/** Autocommit ensures subsequent plan statements can use the newly added value. */
data class AddEnumValue(val name: QualifiedName, val value: String, val before: String? = null, val after: String? = null) : PostgresMigrationStatement {
    init { require(before == null || after == null) }
    override val transactionRequirement = TransactionRequirement.AUTOCOMMIT
    override fun toSql() = "ALTER TYPE ${name.toSql()} ADD VALUE ${literal(value)}" +
        (before?.let { " BEFORE ${literal(it)}" } ?: "") + (after?.let { " AFTER ${literal(it)}" } ?: "")
}
data class RenameEnumValue(val name: QualifiedName, val from: String, val to: String) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TYPE ${name.toSql()} RENAME VALUE ${literal(from)} TO ${literal(to)}"
}
data class AddIdentity(val table: QualifiedName, val column: String, val generation: IdentityGeneration, val options: SequenceOptions = SequenceOptions()) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${table.toSql()} ALTER COLUMN ${quoteIdentifier(column)} ADD GENERATED ${generation.name.replace('_', ' ')} AS IDENTITY" +
        options.toSql().takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
}
data class AlterIdentity(val table: QualifiedName, val column: String, val generation: IdentityGeneration) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${table.toSql()} ALTER COLUMN ${quoteIdentifier(column)} SET GENERATED ${generation.name.replace('_', ' ')}"
}
data class DropIdentity(val table: QualifiedName, val column: String, val ifExists: Boolean = false) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${table.toSql()} ALTER COLUMN ${quoteIdentifier(column)} DROP IDENTITY${if (ifExists) " IF EXISTS" else ""}"
}
data class SetGeneratedExpression(val table: QualifiedName, val column: String, val expression: SqlExpression) : PostgresMigrationStatement {
    override val minimumPostgresVersion = 17
    override val destructiveChange = DestructivePostgresMigrationChange.UPDATE_DATA
    override fun toSql() = "ALTER TABLE ${table.toSql()} ALTER COLUMN ${quoteIdentifier(column)} SET EXPRESSION AS (${expression.text})"
}
data class DropGeneratedExpression(val table: QualifiedName, val column: String) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${table.toSql()} ALTER COLUMN ${quoteIdentifier(column)} DROP EXPRESSION"
}
enum class CommentTarget { TABLE, COLUMN, INDEX, VIEW, MATERIALIZED_VIEW, SEQUENCE, SCHEMA, EXTENSION, TYPE }
/** A null comment removes it. COLUMN requires a table name and a separate column. */
data class SetComment(val target: CommentTarget, val name: QualifiedName, val comment: String?, val column: String? = null) : PostgresMigrationStatement {
    init { require((target == CommentTarget.COLUMN) == (column != null)) }
    override fun toSql() = "COMMENT ON ${target.name.replace('_', ' ')} ${name.toSql()}" +
        (column?.let { ".${quoteIdentifier(it)}" } ?: "") + " IS " + (comment?.let(::literal) ?: "NULL")
}
