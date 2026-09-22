package org.tekfive.keep.schema

import org.jetbrains.exposed.v1.core.Table
import org.tekfive.keep.migration.dynamic.AddConstraint
import org.tekfive.keep.migration.dynamic.ConstraintDefinition
import org.tekfive.keep.migration.dynamic.QualifiedName

/** Constraints with trusted SQL expressions inside an otherwise typed definition. */
sealed class PostgresExpressionConstraintDefinition(
    final override val name: String,
    final override val table: Table,
    val definition: ConstraintDefinition,
) : PostgresTableObject {
    override fun createStatements(context: PostgresRenderContext): List<String> = listOf(AddConstraint(
        QualifiedName(table.tableName.substringAfterLast('.').removeSurrounding("\""), context.schemaName), definition,
    ).toSql())
}

class PostgresCheckConstraintDefinition internal constructor(name: String, table: Table, definition: ConstraintDefinition.Check) :
    PostgresExpressionConstraintDefinition(name, table, definition)

class PostgresExclusionConstraintDefinition internal constructor(name: String, table: Table, definition: ConstraintDefinition.Exclusion) :
    PostgresExpressionConstraintDefinition(name, table, definition)
