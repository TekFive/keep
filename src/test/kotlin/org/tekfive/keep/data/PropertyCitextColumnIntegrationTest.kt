package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.schema.KeepSchema
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class PropertyCitextModel(val label: String)

private object PropertyCitextTable : Table("property_citext_test") {
    val label = column(PropertyCitextModel::label, caseInsensitive = true)
}

class PropertyCitextColumnIntegrationTest {
    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction {
            exec("CREATE EXTENSION IF NOT EXISTS \"${KeepSchema.CITEXT}\"")
            SchemaUtils.create(PropertyCitextTable)
        }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(PropertyCitextTable) }
    }

    @Test
    fun `case insensitive property column uses citext and eq matches different casing`() {
        transaction {
            val databaseType = exec(
                "SELECT udt_name FROM information_schema.columns " +
                    "WHERE table_schema = current_schema() " +
                    "AND table_name = 'property_citext_test' AND column_name = 'label'",
            ) { result ->
                check(result.next())
                result.getString(1)
            }
            assertEquals("citext", databaseType)

            PropertyCitextTable.insert { it[label] = "Hello" }
            PropertyCitextTable.insert { it[label] = "Unrelated" }

            for (query in listOf("HELLO", "hello", "hElLo")) {
                val matches = PropertyCitextTable.selectAll()
                    .where { PropertyCitextTable.label eq query }
                    .map { it[PropertyCitextTable.label] }

                assertEquals(listOf("Hello"), matches, "eq should match $query and preserve stored casing")
            }
        }
    }
}
