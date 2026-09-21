package org.tekfive.keep.location

import org.jetbrains.exposed.v1.core.Table
import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.toJsonObject
import org.tekfive.keep.data.column
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddressTest {

    data class Envelope(val address: Address, val addresses: List<Address>) : ToJsonObject {
        companion object : FromJsonObject<Envelope>
    }

    @Test
    fun `implements JFK JSON interfaces while preserving the address JSON format`() {
        val address = Address("123 Main St", "Springfield", State.IL, "62701")
        val serializer: ToJsonObject = address
        val parser: FromJsonObject<Address> = Address
        val encoded = serializer.toJsonObject()

        assertEquals("IL", encoded["state"].string)
        assertEquals("62701", encoded["zip"].string)
        assertEquals(setOf("street", "city", "state", "zip"), encoded.entries.keys)
        assertEquals(address, parser.fromJson(encoded))
        assertEquals(address.toJson().toJsonString(), encoded.toJsonString())
        assertEquals(address.toJsonString(), serializer.toJsonString())
    }

    @Test
    fun `JFK deserialization supports legacy state ids and zipCode fields`() {
        val encoded = mapOf("state" to State.IL.id, "zipCode" to "62701").toJsonObject()
        val parser: FromJsonObject<Address> = Address
        assertEquals(Address(null, null, State.IL, "62701"), parser.fromJson(encoded))
    }

    @Test
    fun `JFK round trips empty and partial addresses`() {
        for (address in listOf(Address(), Address(null, "Springfield", null, null))) {
            assertEquals(address, Address.fromJson(address.toJsonObject()))
        }
        assertEquals(Address(), Address(null))
        assertEquals(Address(), Address.fromJson(emptyMap<String, Any?>().toJsonObject()))
        assertNull(Address.fromJsonOptional(null))
    }

    @Test
    fun `JFK supports nested addresses and address lists`() {
        val full = Address("123 Main St", "Springfield", State.IL, "62701")
        val envelope = Envelope(full, listOf(full, Address()))
        assertEquals(envelope, Envelope.fromJson(envelope.toJsonObject()))
    }

    @Test
    fun `address companion works as a JSONB property column converter`() {
        val table = object : Table("address_json") {}
        val addressColumn = table.column(Envelope::address, Address)
        val address = Address("123 Main St", "Springfield", State.IL, "62701")
        val stored = addressColumn.columnType.notNullValueToDB(address)
        assertEquals(address, addressColumn.columnType.valueFromDB(stored))
    }

    @Test
    fun `hasFullAddress returns true when all fields are set`() {
        val address = Address("123 Main St", "Springfield", State.IL, "62701")
        assertTrue(address.hasFullAddress)
    }

    @Test
    fun `hasFullAddress returns false when street is null`() {
        val address = Address(null, "Springfield", State.IL, "62701")
        assertFalse(address.hasFullAddress)
    }

    @Test
    fun `hasFullAddress returns false when state is null`() {
        val address = Address("123 Main St", "Springfield", null, "62701")
        assertFalse(address.hasFullAddress)
    }

    @Test
    fun `hasData returns false for empty address`() {
        val address = Address()
        assertFalse(address.hasData)
    }

    @Test
    fun `hasData returns true when any field is set`() {
        val address = Address(null, "Springfield", null, null)
        assertTrue(address.hasData)
    }

    @Test
    fun `isNullOrEmpty returns true for null address`() {
        val address: Address? = null
        assertTrue(address.isNullOrEmpty())
    }

    @Test
    fun `isNullOrEmpty returns true for empty address`() {
        val address: Address? = Address()
        assertTrue(address.isNullOrEmpty())
    }

    @Test
    fun `isNullOrEmpty returns false for address with data`() {
        val address: Address? = Address("123 Main St", null, null, null)
        assertFalse(address.isNullOrEmpty())
    }

    @Test
    fun `isNotNullOrEmpty returns true for address with data`() {
        val address: Address? = Address("123 Main St", "Springfield", State.IL, "62701")
        assertTrue(address.isNotNullOrEmpty())
    }

    @Test
    fun `isNotNullAndFullAddress returns true for complete address`() {
        val address: Address? = Address("123 Main St", "Springfield", State.IL, "62701")
        assertTrue(address.isNotNullAndFullAddress())
    }

    @Test
    fun `isNotNullAndFullAddress returns false for partial address`() {
        val address: Address? = Address("123 Main St", null, null, null)
        assertFalse(address.isNotNullAndFullAddress())
    }

    @Test
    fun `toJsonString produces valid json`() {
        val address = Address("123 Main St", "Springfield", State.IL, "62701")
        val json = address.toJsonString()
        assertTrue(json.contains("123 Main St"))
        assertTrue(json.contains("Springfield"))
    }

    @Test
    fun `json round trip preserves state and zip`() {
        val address = Address("123 Main St", "Springfield", State.IL, "62701")

        assertEquals(address, Address(address.toJson()))
    }

    @Test
    fun `json parsing remains compatible with numeric state and zipCode`() {
        val json = mapOf(
            "street" to "123 Main St",
            "city" to "Springfield",
            "state" to State.IL.id,
            "zipCode" to "62701",
        ).toJsonObject()

        assertEquals(Address("123 Main St", "Springfield", State.IL, "62701"), Address(json))
    }

    @Test
    fun `nullEquivalent treats null and empty addresses as equivalent`() {
        val missing: Address? = null

        assertTrue(missing.nullEquivalent(null))
        assertTrue(missing.nullEquivalent(Address()))
        assertTrue(Address().nullEquivalent(null))
    }

    @Test
    fun `nullEquivalent does not equate a missing address with a populated address`() {
        val missing: Address? = null
        val populated = Address("123 Main St", "Springfield", State.IL, "62701")

        assertFalse(missing.nullEquivalent(populated))
        assertFalse(populated.nullEquivalent(missing))
    }
}
