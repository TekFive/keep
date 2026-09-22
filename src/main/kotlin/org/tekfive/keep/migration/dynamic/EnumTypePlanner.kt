package org.tekfive.keep.migration.dynamic

import org.tekfive.keep.schema.KeepSchema
import org.tekfive.keep.schema.PostgresEnumDefinition
import java.sql.Connection

internal data class EnumTypePlan(
    val creates: List<CreateEnumType>,
    val additions: List<AddEnumValue>,
    val drops: List<DropType>,
)

internal fun planEnumTypes(connection: Connection, schema: KeepSchema): EnumTypePlan {
    require(schema.types.map { it.name }.distinct().size == schema.types.size) { "Duplicate schema type names" }
    val existing = connection.prepareStatement(
        """
        SELECT t.typname, t.typtype, ARRAY(SELECT e.enumlabel FROM pg_enum e WHERE e.enumtypid = t.oid ORDER BY e.enumsortorder),
               EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_type'::regclass AND d.objid = t.oid
                       AND d.refclassid = 'pg_extension'::regclass AND d.deptype = 'e')
        FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace WHERE n.nspname = ?
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, schema.schemaName)
        statement.executeQuery().use { result ->
            buildMap {
                while (result.next()) put(result.getString(1), Triple(result.getString(2),
                    (result.getArray(3).array as Array<*>).map { it.toString() }, result.getBoolean(4)))
            }
        }
    }
    val creates = mutableListOf<CreateEnumType>()
    val additions = mutableListOf<AddEnumValue>()
    schema.types.forEach { definition ->
        when (definition) {
            is PostgresEnumDefinition -> {
                val name = QualifiedName(definition.name, schema.schemaName)
                val actual = existing[definition.name]
                if (actual == null) creates += CreateEnumType(name, definition.values)
                else {
                    require(actual.first == "e" && !actual.third) { "Type ${name.toSql()} is not an application-owned enum" }
                    val known = actual.second.toMutableSet()
                    require(definition.values.filter { it in known } == actual.second) {
                        "Removing or reordering values of ${name.toSql()} requires an explicit data migration"
                    }
                    definition.values.forEachIndexed { index, value ->
                        if (value !in known) {
                            val before = definition.values.drop(index + 1).firstOrNull { it in known }
                            additions += AddEnumValue(name, value, before = before)
                            known += value
                        }
                    }
                }
            }
        }
    }
    val declared = schema.types.mapTo(mutableSetOf()) { it.name }
    val drops = existing.filter { (name, type) -> type.first == "e" && !type.third && name !in declared }
        .keys.sorted().map { DropType(QualifiedName(it, schema.schemaName)) }
    return EnumTypePlan(creates, additions, drops)
}
