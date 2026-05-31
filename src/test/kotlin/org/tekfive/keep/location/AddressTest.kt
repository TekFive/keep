package org.tekfive.keep.location

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
}
