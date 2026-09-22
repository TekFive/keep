package org.tekfive.keep.migration.dynamic

/** Execution constraints are separate from data-loss classification. */
enum class TransactionRequirement { TRANSACTIONAL, AUTOCOMMIT }

sealed interface PostgresMigrationStatement {
    val destructiveChange: DestructivePostgresMigrationChange? get() = null
    val transactionRequirement: TransactionRequirement get() = TransactionRequirement.TRANSACTIONAL
    val minimumPostgresVersion: Int get() = 12
    fun toSql(): String
}

data class CreateSchema(val name: String, val ifNotExists: Boolean = false) : PostgresMigrationStatement {
    override fun toSql() = "CREATE SCHEMA ${if (ifNotExists) "IF NOT EXISTS " else ""}${quoteIdentifier(name)}"
}

data class CreateTable(
    val name: QualifiedName, val columns: List<ColumnDefinition>,
    val constraints: List<ConstraintDefinition> = emptyList(), val ifNotExists: Boolean = false,
) : PostgresMigrationStatement {
    override val minimumPostgresVersion get() = if (constraints.any { it is ConstraintDefinition.Unique && it.nullsNotDistinct }) 15 else 12
    override fun toSql() = "CREATE TABLE ${if (ifNotExists) "IF NOT EXISTS " else ""}${name.toSql()} (" +
        (columns.map { it.toSql() } + constraints.map { it.toSql() }).joinToString(", ") + ")"
}

data class AddColumn(val table: QualifiedName, val column: ColumnDefinition, val ifNotExists: Boolean = false) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${table.toSql()} ADD COLUMN ${if (ifNotExists) "IF NOT EXISTS " else ""}${column.toSql()}"
}
data class DropColumn(val table: QualifiedName, val column: String, val ifExists: Boolean = false, val behavior: DropBehavior = DropBehavior.RESTRICT) : PostgresMigrationStatement {
    override val destructiveChange = DestructivePostgresMigrationChange.DROP_COLUMN
    override fun toSql() = "ALTER TABLE ${table.toSql()} DROP COLUMN ${if (ifExists) "IF EXISTS " else ""}${quoteIdentifier(column)} $behavior"
}
data class RenameColumn(val table: QualifiedName, val from: String, val to: String) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${table.toSql()} RENAME COLUMN ${quoteIdentifier(from)} TO ${quoteIdentifier(to)}"
}
data class AlterColumnType(val table: QualifiedName, val column: String, val type: PostgresType, val using: SqlExpression? = null) : PostgresMigrationStatement {
    override val destructiveChange = DestructivePostgresMigrationChange.ALTER_COLUMN_TYPE
    override fun toSql() = "ALTER TABLE ${table.toSql()} ALTER COLUMN ${quoteIdentifier(column)} TYPE ${type.sql}" + (using?.let { " USING (${it.text})" } ?: "")
}
data class SetColumnDefault(val table: QualifiedName, val column: String, val expression: SqlExpression) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${table.toSql()} ALTER COLUMN ${quoteIdentifier(column)} SET DEFAULT ${expression.text}"
}
data class DropColumnDefault(val table: QualifiedName, val column: String) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${table.toSql()} ALTER COLUMN ${quoteIdentifier(column)} DROP DEFAULT"
}
data class SetNotNull(val table: QualifiedName, val column: String) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${table.toSql()} ALTER COLUMN ${quoteIdentifier(column)} SET NOT NULL"
}
data class DropNotNull(val table: QualifiedName, val column: String) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${table.toSql()} ALTER COLUMN ${quoteIdentifier(column)} DROP NOT NULL"
}
data class AddConstraint(val table: QualifiedName, val definition: ConstraintDefinition, val notValid: Boolean = false) : PostgresMigrationStatement {
    init { require(!notValid || definition is ConstraintDefinition.ForeignKey || definition is ConstraintDefinition.Check) }
    override val minimumPostgresVersion get() = if (definition is ConstraintDefinition.Unique && definition.nullsNotDistinct) 15 else 12
    override fun toSql() = "ALTER TABLE ${table.toSql()} ADD ${definition.toSql()}${if (notValid) " NOT VALID" else ""}"
}
data class DropConstraint(val table: QualifiedName, val name: String, val ifExists: Boolean = false, val behavior: DropBehavior = DropBehavior.RESTRICT, val tableIfExists: Boolean = false) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${if (tableIfExists) "IF EXISTS " else ""}${table.toSql()} DROP CONSTRAINT ${if (ifExists) "IF EXISTS " else ""}${quoteIdentifier(name)} $behavior"
}
data class ValidateConstraint(val table: QualifiedName, val name: String) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${table.toSql()} VALIDATE CONSTRAINT ${quoteIdentifier(name)}"
}
data class RenameConstraint(val table: QualifiedName, val from: String, val to: String) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${table.toSql()} RENAME CONSTRAINT ${quoteIdentifier(from)} TO ${quoteIdentifier(to)}"
}

data class CreateIndex(val definition: IndexDefinition, val concurrently: Boolean = false, val ifNotExists: Boolean = false) : PostgresMigrationStatement {
    override val transactionRequirement get() = if (concurrently) TransactionRequirement.AUTOCOMMIT else TransactionRequirement.TRANSACTIONAL
    override val minimumPostgresVersion get() = if (definition.nullsNotDistinct) 15 else 12
    override fun toSql() = with(definition) {
        "CREATE ${if (unique) "UNIQUE " else ""}INDEX ${if (concurrently) "CONCURRENTLY " else ""}${if (ifNotExists) "IF NOT EXISTS " else ""}${quoteIdentifier(name.name)} ON ${table.toSql()}" +
            (method?.let { " USING ${quoteIdentifier(it)}" } ?: "") + " (${keys.joinToString { it.text }})" +
            (if (include.isEmpty()) "" else " INCLUDE (${ConstraintDefinition.names(include)})") +
            (if (nullsNotDistinct) " NULLS NOT DISTINCT" else "") + (predicate?.let { " WHERE ${it.text}" } ?: "")
    }
}
data class DropIndex(val name: QualifiedName, val concurrently: Boolean = false, val ifExists: Boolean = false, val behavior: DropBehavior = DropBehavior.RESTRICT) : PostgresMigrationStatement {
    init { require(!concurrently || behavior != DropBehavior.CASCADE) }
    override val transactionRequirement get() = if (concurrently) TransactionRequirement.AUTOCOMMIT else TransactionRequirement.TRANSACTIONAL
    override fun toSql() = "DROP INDEX ${if (concurrently) "CONCURRENTLY " else ""}${if (ifExists) "IF EXISTS " else ""}${name.toSql()} $behavior"
}

data class CreateSequence(val name: QualifiedName, val options: SequenceOptions = SequenceOptions(), val ifNotExists: Boolean = false) : PostgresMigrationStatement {
    override fun toSql() = "CREATE SEQUENCE ${if (ifNotExists) "IF NOT EXISTS " else ""}${name.toSql()}${options.toSql()}"
}
data class AlterSequence(val name: QualifiedName, val options: SequenceOptions) : PostgresMigrationStatement {
    init { require(options.toSql().isNotEmpty()) }
    override fun toSql() = "ALTER SEQUENCE ${name.toSql()}${options.toSql()}"
}
data class SetSequenceOwner(val name: QualifiedName, val table: QualifiedName?, val column: String? = null) : PostgresMigrationStatement {
    init { require((table == null) == (column == null)) }
    override fun toSql() = "ALTER SEQUENCE ${name.toSql()} OWNED BY " + (table?.let { "${it.toSql()}.${quoteIdentifier(column!!)}" } ?: "NONE")
}

data class CreateView(val name: QualifiedName, val query: SqlQuery, val columns: List<String> = emptyList()) : PostgresMigrationStatement {
    override fun toSql() = "CREATE VIEW ${name.toSql()}${viewColumns(columns)} AS ${query.text}"
}
data class CreateOrReplaceView(val name: QualifiedName, val query: SqlQuery, val columns: List<String> = emptyList()) : PostgresMigrationStatement {
    override fun toSql() = "CREATE OR REPLACE VIEW ${name.toSql()}${viewColumns(columns)} AS ${query.text}"
}
private fun viewColumns(columns: List<String>) = if (columns.isEmpty()) "" else " (${ConstraintDefinition.names(columns)})"

data class CreateMaterializedView(val name: QualifiedName, val query: SqlQuery, val withData: Boolean = true) : PostgresMigrationStatement {
    override fun toSql() = "CREATE MATERIALIZED VIEW ${name.toSql()} AS ${query.text} WITH ${if (withData) "" else "NO "}DATA"
}
data class RefreshMaterializedView(val name: QualifiedName, val concurrently: Boolean = false, val withData: Boolean = true) : PostgresMigrationStatement {
    init { require(!concurrently || withData) }
    override fun toSql() = "REFRESH MATERIALIZED VIEW ${if (concurrently) "CONCURRENTLY " else ""}${name.toSql()} WITH ${if (withData) "" else "NO "}DATA"
}

data class CreateFunction(val definition: FunctionDefinition) : PostgresMigrationStatement {
    override fun toSql() = renderFunction(definition, false)
}
data class CreateOrReplaceFunction(val definition: FunctionDefinition) : PostgresMigrationStatement {
    override fun toSql() = renderFunction(definition, true)
}
private fun renderFunction(definition: FunctionDefinition, replace: Boolean) = with(definition) {
    "CREATE ${if (replace) "OR REPLACE " else ""}FUNCTION ${name.toSql()}(${arguments.joinToString { it.toSql() }})\n" +
        "RETURNS ${returns.sql}\nLANGUAGE ${quoteIdentifier(language)}\nAS ${body.quoted()}"
}
data class DropFunction(val name: QualifiedName, val argumentTypes: List<PostgresType> = emptyList(), val ifExists: Boolean = false, val behavior: DropBehavior = DropBehavior.RESTRICT) : PostgresMigrationStatement {
    override val destructiveChange = DestructivePostgresMigrationChange.DROP_FUNCTION
    override fun toSql() = "DROP FUNCTION ${if (ifExists) "IF EXISTS " else ""}${name.toSql()}(${argumentTypes.joinToString { it.sql }}) $behavior"
}
data class CreateTrigger(val definition: TriggerDefinition) : PostgresMigrationStatement {
    override fun toSql() = with(definition) {
        "CREATE TRIGGER ${quoteIdentifier(name)} ${timing.name.replace('_', ' ')} ${events.joinToString(" OR ")} ON ${table.toSql()} FOR EACH $scope" +
            (condition?.let { " WHEN (${it.text})" } ?: "") + " EXECUTE FUNCTION ${function.toSql()}(${arguments.joinToString(transform = ::literal)})"
    }
}
data class DropTrigger(val table: QualifiedName, val name: String, val ifExists: Boolean = false, val behavior: DropBehavior = DropBehavior.RESTRICT) : PostgresMigrationStatement {
    override fun toSql() = "DROP TRIGGER ${if (ifExists) "IF EXISTS " else ""}${quoteIdentifier(name)} ON ${table.toSql()} $behavior"
}
data class RenameTrigger(val table: QualifiedName, val from: String, val to: String) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TRIGGER ${quoteIdentifier(from)} ON ${table.toSql()} RENAME TO ${quoteIdentifier(to)}"
}
data class SetTriggerEnabled(val table: QualifiedName, val name: String, val enabled: TriggerEnabled) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${table.toSql()} ${enabled.name.replace('_', ' ')} TRIGGER ${quoteIdentifier(name)}"
}

data class DropTable(val name: QualifiedName, val ifExists: Boolean = false, val behavior: DropBehavior = DropBehavior.RESTRICT) : PostgresMigrationStatement {
    override val destructiveChange = DestructivePostgresMigrationChange.DROP_TABLE
    override fun toSql() = "DROP TABLE ${if (ifExists) "IF EXISTS " else ""}${name.toSql()} $behavior"
}

data class DropForeignTable(val name: QualifiedName, val ifExists: Boolean = false, val behavior: DropBehavior = DropBehavior.RESTRICT) : PostgresMigrationStatement {
    override val destructiveChange = DestructivePostgresMigrationChange.DROP_TABLE
    override fun toSql() = "DROP FOREIGN TABLE ${if (ifExists) "IF EXISTS " else ""}${name.toSql()} $behavior"
}

data class DropView(val name: QualifiedName, val ifExists: Boolean = false, val behavior: DropBehavior = DropBehavior.RESTRICT) : PostgresMigrationStatement {
    override val destructiveChange = DestructivePostgresMigrationChange.DROP_VIEW
    override fun toSql() = "DROP VIEW ${if (ifExists) "IF EXISTS " else ""}${name.toSql()} $behavior"
}

data class DropMaterializedView(val name: QualifiedName, val ifExists: Boolean = false, val behavior: DropBehavior = DropBehavior.RESTRICT) : PostgresMigrationStatement {
    override val destructiveChange = DestructivePostgresMigrationChange.DROP_MATERIALIZED_VIEW
    override fun toSql() = "DROP MATERIALIZED VIEW ${if (ifExists) "IF EXISTS " else ""}${name.toSql()} $behavior"
}

data class DropSequence(val name: QualifiedName, val ifExists: Boolean = false, val behavior: DropBehavior = DropBehavior.RESTRICT) : PostgresMigrationStatement {
    override val destructiveChange = DestructivePostgresMigrationChange.DROP_SEQUENCE
    override fun toSql() = "DROP SEQUENCE ${if (ifExists) "IF EXISTS " else ""}${name.toSql()} $behavior"
}

data class DropType(val name: QualifiedName, val ifExists: Boolean = false, val behavior: DropBehavior = DropBehavior.RESTRICT) : PostgresMigrationStatement {
    override val destructiveChange = DestructivePostgresMigrationChange.DROP_TYPE
    override fun toSql() = "DROP TYPE ${if (ifExists) "IF EXISTS " else ""}${name.toSql()} $behavior"
}

data class RenameTable(val name: QualifiedName, val to: String) : PostgresMigrationStatement {
    override fun toSql() = "ALTER TABLE ${name.toSql()} RENAME TO ${quoteIdentifier(to)}"
}

data class RenameIndex(val name: QualifiedName, val to: String) : PostgresMigrationStatement {
    override fun toSql() = "ALTER INDEX ${name.toSql()} RENAME TO ${quoteIdentifier(to)}"
}

data class RenameSequence(val name: QualifiedName, val to: String) : PostgresMigrationStatement {
    override fun toSql() = "ALTER SEQUENCE ${name.toSql()} RENAME TO ${quoteIdentifier(to)}"
}
