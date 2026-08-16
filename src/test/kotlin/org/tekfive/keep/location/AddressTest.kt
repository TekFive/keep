package org.tekfive.keep.location

import org.tekfive.jfk.toJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddressTest {

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
