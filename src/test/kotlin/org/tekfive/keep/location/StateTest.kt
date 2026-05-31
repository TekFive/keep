package org.tekfive.keep.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StateTest {

    @Test
    fun `all 51 states and DC are defined`() {
        assertEquals(51, State.entries.size)
    }

    @Test
    fun `State has correct id and displayText`() {
        assertEquals(1, State.AL.id)
        assertEquals("Alabama", State.AL.displayName)
    }

    @Test
    fun `DC is the last entry with id 51`() {
        assertEquals(51, State.DC.id)
        assertEquals("District of Columbia", State.DC.displayName)
    }

    @Test
    fun `mapOptional returns state for valid id`() {
        val state = State.mapOptional(1)
        assertNotNull(state)
        assertEquals(State.AL, state)
    }

    @Test
    fun `mapOptional returns null for null id`() {
        val state = State.mapOptional(null)
        assertNull(state)
    }
}
