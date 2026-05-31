package org.tekfive.keep.data

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FindPagedTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction { SchemaUtils.create(SimpleTable) }
        transaction {
            for (i in 1..15) {
                SimpleTable.create(SimpleData("item-$i", i))
            }
        }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(SimpleTable) }
    }

    @Test
    fun `findPaged returns correct page and total for page 1`() {
        transaction {
            val predicate = Op.TRUE
            val result = SimpleTable.findPaged(predicate, page = 1, size = 5)

            assertEquals(5, result.data.size)
            assertEquals(15, result.total)
            assertEquals(1, result.page)
            assertEquals(5, result.size)
        }
    }

    @Test
    fun `findPaged returns second page with correct offset`() {
        transaction {
            val predicate = Op.TRUE
            val first = SimpleTable.findPaged(
                predicate, page = 1, size = 5,
                SimpleTable.score to SortOrder.ASC,
            )
            val second = SimpleTable.findPaged(
                predicate, page = 2, size = 5,
                SimpleTable.score to SortOrder.ASC,
            )

            assertEquals(5, second.data.size)
            assertEquals(15, second.total)
            assertEquals(2, second.page)

            val firstScores = first.data.map { it.score }
            val secondScores = second.data.map { it.score }
            assertEquals(listOf(1, 2, 3, 4, 5), firstScores)
            assertEquals(listOf(6, 7, 8, 9, 10), secondScores)
        }
    }

    @Test
    fun `findPaged with predicate filters the total count`() {
        transaction {
            val predicate = SimpleTable.score greaterEq 10
            val result = SimpleTable.findPaged(predicate, page = 1, size = 5)

            assertEquals(6, result.total)
            assertEquals(5, result.data.size)
            assertEquals(1, result.page)
            assertEquals(5, result.size)
        }
    }

    @Test
    fun `toJson serializes PagedResult correctly`() {
        transaction {
            val predicate = Op.TRUE
            val result = SimpleTable.findPaged(
                predicate, page = 2, size = 5,
                SimpleTable.score to SortOrder.ASC,
            )

            val jsonObject = result.toJson { item ->
                "name" set item.name
                "score" set item.score
            }

            assertEquals(15, jsonObject["total"].reqInt)
            assertEquals(2, jsonObject["page"].reqInt)
            assertEquals(5, jsonObject["size"].reqInt)

            val dataArray = jsonObject.reqArray("data")
            assertEquals(5, dataArray.size)
            val firstItem = dataArray[0].reqObj
            assertEquals("item-6", firstItem.reqString("name"))
            assertEquals(6, firstItem["score"].reqInt)
        }
    }
}
