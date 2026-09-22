package org.tekfive.keep.migration.dynamic

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresIndexDefinition
import org.tekfive.keep.schema.PostgresRenderContext
import org.tekfive.keep.schema.PostgresTargetVersion
import java.sql.Connection
import java.util.UUID

internal data class IndexPlan(val drops: List<DropIndex>, val creates: List<CreateIndex>)

/** Compare server-normalized index definitions rather than raw predicate/expression spelling. */
internal fun planIndexes(connection: Connection, schema: KeepSchema): IndexPlan {
    val context = PostgresRenderContext(schema.schemaName, PostgresTargetVersion(connection.metaData.databaseMajorVersion))
    val declared = schema.declaredPostgresObjects.filterIsInstance<PostgresIndexDefinition>()
    val drops = mutableListOf<DropIndex>()
    val creates = mutableListOf<CreateIndex>()
    schema.tables.forEach { table ->
        val tableName = SqlCursor(TransactionManager.current().identity(table)).name()
        val definitions = declared.filter { it.table == table }
        val wanted = definitions.mapTo(mutableSetOf()) { it.name } + table.indices.map { it.indexName }
        val existing = readIndexes(connection, tableName.toSql())
        existing.filter { (name, value) -> name !in wanted && !value.constraint }.forEach { (name, _) ->
            drops += DropIndex(QualifiedName(name, schema.schemaName))
        }
        definitions.forEach { definition ->
            val desired = definition.definition(context)
            val actual = existing[definition.name]
            require(actual?.constraint != true) { "Index ${definition.name} belongs to a constraint" }
            if (actual == null) {
                // Index names share the relation namespace across this schema.
                connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
                    statement.setString(1, desired.name.toSql())
                    statement.executeQuery().use { result ->
                        result.next()
                        require(!result.getBoolean(1)) { "Index name ${desired.name.toSql()} is already used by another relation" }
                    }
                }
                creates += CreateIndex(desired)
            } else {
                val expected = canonicalIndex(connection, definition, desired)
                if (!actual.valid || actual.shape != expected.shape) {
                    drops += DropIndex(desired.name)
                    creates += CreateIndex(desired)
                }
            }
        }
    }
    return IndexPlan(drops.distinct(), creates)
}

private data class IndexShape(
    val method: String, val unique: Boolean, val keys: List<String>, val include: List<String>,
    val predicate: String?, val nullsNotDistinct: Boolean,
)
private data class ExistingIndex(val shape: IndexShape, val valid: Boolean, val constraint: Boolean)

private fun canonicalIndex(connection: Connection, definition: PostgresIndexDefinition, desired: IndexDefinition): ExistingIndex {
    val suffix = UUID.randomUUID().toString().replace("-", "")
    val temporary = QualifiedName("keep_index_$suffix", "pg_temp")
    val index = QualifiedName("keep_idx_$suffix", "pg_temp")
    val savepoint = connection.setSavepoint()
    try {
        val columns = definition.table.columns.joinToString { "${quoteIdentifier(it.nameUnquoted())} ${it.columnType.sqlType()}" }
        connection.createStatement().use { statement ->
            statement.execute("CREATE TEMPORARY TABLE ${temporary.toSql()} ($columns)")
            statement.execute(CreateIndex(desired.copy(name = index, table = temporary)).toSql())
        }
        return readIndexes(connection, temporary.toSql()).getValue(index.name)
    } finally {
        connection.rollback(savepoint)
        connection.releaseSavepoint(savepoint)
    }
}

private fun readIndexes(connection: Connection, table: String): Map<String, ExistingIndex> = connection.prepareStatement(
    """
    SELECT ci.relname, am.amname, i.indisunique,
           ARRAY(SELECT pg_get_indexdef(i.indexrelid, k, true) FROM generate_series(1, i.indnkeyatts) k),
           ARRAY(SELECT a.attname FROM unnest(i.indkey) WITH ORDINALITY k(num, ord)
                 JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.num WHERE k.ord > i.indnkeyatts ORDER BY k.ord),
           pg_get_expr(i.indpred, i.indrelid, true),
           ${if (connection.metaData.databaseMajorVersion >= 15) "i.indnullsnotdistinct" else "FALSE"}, i.indisvalid,
           EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid),
           EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = i.indexrelid
                   AND d.refclassid = 'pg_extension'::regclass AND d.deptype = 'e')
    FROM pg_index i JOIN pg_class ci ON ci.oid = i.indexrelid JOIN pg_am am ON am.oid = ci.relam
    WHERE i.indrelid = to_regclass(?)
    """.trimIndent()
).use { statement ->
    statement.setString(1, table)
    statement.executeQuery().use { result ->
        buildMap {
            while (result.next()) {
                if (result.getBoolean(10)) continue
                put(result.getString(1), ExistingIndex(IndexShape(result.getString(2), result.getBoolean(3),
                    (result.getArray(4).array as Array<*>).map { it.toString() },
                    (result.getArray(5).array as Array<*>).map { it.toString() }, result.getString(6), result.getBoolean(7)),
                    result.getBoolean(8), result.getBoolean(9)))
            }
        }
    }
}
