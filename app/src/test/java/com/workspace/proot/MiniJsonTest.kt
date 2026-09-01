package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun optBooleanCoercesNumbers() {
        val o = MiniJson.parse(
            """{"a":1,"b":0,"c":1.0,"d":0.0,"e":true,"f":false}"""
        )
        assertTrue(o.optBoolean("a", false))
        assertFalse(o.optBoolean("b", true))
        assertTrue(o.optBoolean("c", false))
        assertFalse(o.optBoolean("d", true))
        assertTrue(o.optBoolean("e", false))
        assertFalse(o.optBoolean("f", true))
    }

    @Test
    fun optBooleanCoercesStrings() {
        val o = MiniJson.parse(
            """{"a":"true","b":"1","c":"false","d":"0","e":"yes","f":"no","g":"x"}"""
        )
        assertTrue(o.optBoolean("a", false))
        assertTrue(o.optBoolean("b", false))
        assertFalse(o.optBoolean("c", true))
        assertFalse(o.optBoolean("d", true))
        assertTrue(o.optBoolean("e", false))
        assertFalse(o.optBoolean("f", true))
        assertTrue(o.optBoolean("g", true))
        assertFalse(o.optBoolean("g", false))
    }

    @Test
    fun optBooleanMissingUsesDefault() {
        val o = MiniJson.parse("""{}""")
        assertTrue(o.optBoolean("missing", true))
        assertFalse(o.optBoolean("missing", false))
    }

    @Test
    fun numericCoercion() {
        val o = MiniJson.parse("""{"i":42,"l":9000000000,"d":1.5,"n":-3}""")
        assertEquals(42, o.optInt("i", 0))
        assertEquals(9000000000L.toInt(), o.optInt("l", 0))
        assertEquals(1, o.optInt("d", 0))
        assertEquals(-3, o.optInt("n", 0))
        assertEquals(1.5, o.optDouble("d", 0.0), 0.0001)
        assertEquals(42.0, o.optDouble("i", 0.0), 0.0001)
    }

    @Test
    fun roundTripNested() {
        val o = MiniJson.parse(
            """{"a":[1,"x",true],"b":{"c":"y"}}"""
        )
        val arr = o.optArr("a")!!
        assertEquals(3, arr.length())
        assertEquals(1L, arr.raw(0))
        assertEquals("x", arr.getString(1))
        assertEquals(true, arr.raw(2))
        assertEquals("y", o.optObj("b")?.optString("c", ""))
    }
}
