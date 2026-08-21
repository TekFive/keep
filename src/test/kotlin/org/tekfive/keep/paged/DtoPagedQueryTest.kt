package org.tekfive.keep.paged

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
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

class DtoPagedQueryTest {
    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction {
            SchemaUtils.drop(DtoPagedQueryTestTable)
            SchemaUtils.create(DtoPagedQueryTestTable)
            (1..13).forEach { score ->
                DtoPagedQueryTestTable.create(
                    DtoPagedQueryTestData(
                        name = "item${score.toString().padStart(2, '0')}",
                        score = score,
                        active = score <= 12,
                    ),
                )
            }
        }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(DtoPagedQueryTestTable) }
    }

    @Test
    fun `executePage returns typed DTOs with paging metadata and requested sorting`() {
        val page = transaction {
            TestDtoPagedQuery(
                parameters(
                    mapOf(
                        "page" to listOf("2"),
                        "size" to listOf("10"),
                        "sort" to listOf("score:asc"),
                    ),
                ),
            ).executePage()
        }

        assertEquals(12, page.total)
        assertEquals(2, page.page)
        assertEquals(10, page.size)
        assertEquals(
            listOf(
                DtoPagedQueryTestItem("item11", 11),
                DtoPagedQueryTestItem("item12", 12),
            ),
            page.data,
        )
    }

    @Test
    fun `executePage applies inherited search filters and default sorting`() {
        val page = transaction {
            TestDtoPagedQuery(
                parameters(
                    mapOf(
                        "q" to listOf("ITEM1"),
                        "minimumScore" to listOf("11"),
                    ),
                ),
            ).executePage()
        }

        assertEquals(2, page.total)
        assertEquals(
            listOf(
                DtoPagedQueryTestItem("item12", 12),
                DtoPagedQueryTestItem("item11", 11),
            ),
            page.data,
        )
    }

    private fun parameters(raw: Map<String, List<String>>): HttpRequestParameters =
        HttpRequestParameters({ raw }, DefaultKviashConfiguration)
}

class DtoPagedQueryTestData(
    val name: String,
    val score: Int,
    val active: Boolean,
) : Data()

object DtoPagedQueryTestTable : DataTable<DtoPagedQueryTestData>("dto_paged_query_test") {
    val name = varchar("name", 64)
    val score = integer("score")
    val active = bool("active")
}

data class DtoPagedQueryTestItem(
    val name: String,
    val score: Int,
)

class TestDtoPagedQuery(parameters: HttpRequestParameters) :
    DtoPagedQuery<DtoPagedQueryTestItem>(DtoPagedQueryTestTable, parameters) {

    init {
        returnColumns(DtoPagedQueryTestTable.name, DtoPagedQueryTestTable.score)
        addSearchedColumns(DtoPagedQueryTestTable.name)
        setDefaultSort(DtoPagedQueryTestTable.score to SortOrder.DESC)
    }

    override fun basePredicate(): Op<Boolean> = DtoPagedQueryTestTable.active eq true

    override fun filters(parameters: HttpRequestParameters): List<Op<Boolean>> =
        listOfNotNull(
            parameters.getInt("minimumScore")?.let { DtoPagedQueryTestTable.score greaterEq it },
        )

    override fun mapRow(row: ResultRow): DtoPagedQueryTestItem =
        DtoPagedQueryTestItem(
            name = row[DtoPagedQueryTestTable.name],
            score = row[DtoPagedQueryTestTable.score],
        )
}
