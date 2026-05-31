package org.tekfive.keep.data

import org.tekfive.jfk.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DataTest {

    // -- Simple (flat) Data subclass ------------------------------------------

    @Test
    fun `equal when same type and same property values`() {
        assertEquals(SimpleData("alice", 42), SimpleData("alice", 42))
    }

    @Test
    fun `not equal when val property differs`() {
        assertNotEquals(SimpleData("alice", 42), SimpleData("bob", 42))
    }

    @Test
    fun `not equal when var property differs`() {
        assertNotEquals(SimpleData("alice", 42), SimpleData("alice", 99))
    }

    @Test
    fun `equal instances have same hashCode`() {
        assertEquals(SimpleData("alice", 42).hashCode(), SimpleData("alice", 42).hashCode())
    }

    @Test
    fun `identity check short-circuits`() {
        val data = SimpleData("alice", 42)
        assertEquals(data, data)
    }

    @Test
    fun `not equal to null`() {
        assertNotEquals<Any?>(SimpleData("alice", 42), null)
    }

    @Test
    fun `not equal to different type with same values`() {
        // WidgetData also has a String val and an Int var, but it's a different class.
        val simple = SimpleData("x", 1)
        val widget = WidgetData("x", 1)
        assertNotEquals<Data>(simple, widget)
    }

    // -- Mutated var property -------------------------------------------------

    @Test
    fun `equals reflects mutated var`() {
        val a = SimpleData("alice", 42)
        val b = SimpleData("alice", 42)
        assertEquals(a, b)

        b.score = 100
        assertNotEquals(a, b)
    }

    @Test
    fun `hashCode reflects mutated var`() {
        val data = SimpleData("alice", 42)
        val hashBefore = data.hashCode()

        data.score = 100
        val hashAfter = data.hashCode()

        assertNotEquals(hashBefore, hashAfter)
    }

    // -- Database id is excluded ----------------------------------------------

    @Test
    fun `equals ignores database id`() {
        val a = SimpleData("alice", 42)
        val b = SimpleData("alice", 42)
        a.linkToDB(1)
        b.linkToDB(2)

        assertEquals(a, b)
    }

    @Test
    fun `hashCode ignores database id`() {
        val a = SimpleData("alice", 42)
        val b = SimpleData("alice", 42)
        a.linkToDB(1)

        assertEquals(a.hashCode(), b.hashCode())
    }

    // -- Hierarchy: LeafData -> BaseData -> Data ------------------------------

    @Test
    fun `hierarchy equal when all properties match`() {
        assertEquals(LeafData("bob", true, 99), LeafData("bob", true, 99))
    }

    @Test
    fun `hierarchy not equal when base class val differs`() {
        assertNotEquals(LeafData("bob", true, 99), LeafData("other", true, 99))
    }

    @Test
    fun `hierarchy not equal when base class var differs`() {
        assertNotEquals(LeafData("bob", true, 99), LeafData("bob", false, 99))
    }

    @Test
    fun `hierarchy not equal when leaf class var differs`() {
        assertNotEquals(LeafData("bob", true, 99), LeafData("bob", true, 50))
    }

    @Test
    fun `hierarchy equal instances have same hashCode`() {
        assertEquals(
            LeafData("bob", true, 99).hashCode(),
            LeafData("bob", true, 99).hashCode()
        )
    }

    // -- HashMap behavior -----------------------------------------------------

    @Test
    fun `works correctly as HashMap key`() {
        val map = HashMap<Data, String>()
        val key = SimpleData("alice", 42)
        map[key] = "found"

        assertEquals("found", map[SimpleData("alice", 42)])
    }

    // -- toString -------------------------------------------------------------

    @Test
    fun `toString includes class name and properties`() {
        val data = SimpleData("alice", 42)
        assertEquals("SimpleData(name=alice, score=42)", data.toString())
    }

    @Test
    fun `toString includes id when linked to db`() {
        val data = SimpleData("alice", 42)
        data.linkToDB(7)
        assertEquals("SimpleData(id=7, name=alice, score=42)", data.toString())
    }

    @Test
    fun `toString omits id when not linked to db`() {
        val data = SimpleData("alice", 42)
        assertTrue(!data.toString().contains("id="))
    }

    @Test
    fun `toString alphabetizes properties`() {
        val data = LeafData("bob", true, 99)
        assertEquals("LeafData(active=true, name=bob, score=99)", data.toString())
    }

    @Test
    fun `toString masks sensitive property names`() {
        val data = SecretData("webhook-1", "s3cr3t", "hunter2")
        val result = data.toString()
        assertEquals("SecretData(name=webhook-1, password=***, sharedSecret=***)", result)
    }

    @Test
    fun `toString does not mask Id-suffixed property names`() {
        val data = KeyIdData("ai-analysis", 42)
        val result = data.toString()
        assertEquals("KeyIdData(credentialTypeId=ai-analysis, triggerNodeId=42)", result)
    }

    @Test
    fun `toString shows summary for JsonContainer values`() {
        val json = JsonObject(mapOf("a" to 1, "b" to 2))
        val data = JsonPropertyData("test", json)
        val result = data.toString()
        assertEquals("JsonPropertyData(label=test, payload=JsonObject(2))", result)
    }

    @Test
    fun `toString shows summary for ByteArray values`() {
        val data = ByteArrayData("test", byteArrayOf(1, 2, 3))
        val result = data.toString()
        assertEquals("ByteArrayData(content=ByteArray(3), label=test)", result)
    }

    @Test
    fun `toString masks properties listed in sensitiveProperties`() {
        val data = SensitiveOverrideData("public", "private notes")
        val result = data.toString()
        assertEquals("SensitiveOverrideData(name=public, notes=***)", result)
    }

    @Test
    fun `toString never masks Id-suffixed properties even with sensitive pattern`() {
        val data = KeyIdData("ai-analysis", 42)
        val result = data.toString()
        assertTrue(result.contains("credentialTypeId=ai-analysis"))
        assertTrue(result.contains("triggerNodeId=42"))
    }
}
