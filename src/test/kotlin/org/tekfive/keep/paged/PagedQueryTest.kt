package org.tekfive.keep.paged

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.data.Data
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.TestDatabase
import org.tekfive.kviash.DefaultKviashConfiguration
import org.tekfive.kviash.http.HttpRequestParameters
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PagedQueryTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction {
            SchemaUtils.create(PagedQueryTestTable)
            PagedQueryTestTable.create(PagedQueryTestData("workflow", 42, 7))
        }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(PagedQueryTestTable) }
    }

    @Test
    fun `default column names are serialized as json property names`() {
        val json = transaction { PagedQueryTestQuery(parameters()).execute() }
        val row = json.reqArray("data")[0].reqObj

        assertEquals("workflow", row.reqString("resourceType"))
        assertEquals(42, row["resourceTypeId"].reqInt)
        assertFalse(row.containsKey("resource_type"))
        assertFalse(row.containsKey("resource_type_id"))
    }

    @Test
    fun `automatic naming uses the table property name over the column name`() {
        val json = transaction { PagedQueryTestQuery(parameters()).execute() }
        val row = json.reqArray("data")[0].reqObj

        // The column is named environment_id but the table property is `environment` —
        // the property name wins so the JSON matches the Data class.
        assertEquals(7, row["environment"].reqInt)
        assertFalse(row.containsKey("environmentId"))
    }

    private fun parameters(raw: Map<String, List<String>> = emptyMap()): HttpRequestParameters {
        return HttpRequestParameters({ raw }, DefaultKviashConfiguration)
    }
}

class PagedQueryTestData(val resourceType: String, val resourceTypeId: Int, val environment: Int) : Data()

object PagedQueryTestTable : DataTable<PagedQueryTestData>("paged_query_test") {
    val resourceType = varchar("resource_type", 64)
    val resourceTypeId = integer("resource_type_id")
    val environment = integer("environment_id")
}

class PagedQueryTestQuery(parameters: HttpRequestParameters) : PagedQuery(PagedQueryTestTable, parameters) {
    init {
        returnColumn(PagedQueryTestTable.resourceType)
        returnColumn(PagedQueryTestTable.resourceTypeId)
        returnColumn(PagedQueryTestTable.environment)
    }
}
