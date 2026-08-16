package org.tekfive.keep.data

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UniqueNameData(val name: String) : Data()

object UniqueNamesTable : DataTable<UniqueNameData>("unique_names"), TableWithUniqueName {
    override val name = varchar("name", 255).uniqueIndex()
}

class TableWithUniqueNameTest {

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction { SchemaUtils.create(UniqueNamesTable) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(UniqueNamesTable) }
    }

    @Test
    fun `isNameAlreadyTaken is true for an existing name`() {
        transaction {
            UniqueNamesTable.create(UniqueNameData("existing"))

            assertTrue(UniqueNamesTable.isNameAlreadyTaken("existing"))
        }
    }

    @Test
    fun `isNameAlreadyTaken is false for an available name`() {
        transaction {
            assertFalse(UniqueNamesTable.isNameAlreadyTaken("available"))
        }
    }

    @Test
    fun `isNameAlreadyTaken excludes the candidate row`() {
        transaction {
            val candidate = UniqueNamesTable.create(UniqueNameData("unchanged"))

            assertFalse(UniqueNamesTable.isNameAlreadyTaken("unchanged", candidate.id))
        }
    }
}
