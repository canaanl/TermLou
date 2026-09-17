package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BanditTunerTest {

    @Test
    fun `missing weight reads zero`() {
        assertEquals(0f, BanditTuner.weightOf(emptyMap(), "shell", "a"), 0f)
        assertEquals(0f, BanditTuner.weightOf(mapOf("vim" to mapOf("a" to 0.5f)), "shell", "a"), 0f)
    }

    @Test
    fun `tap adds step and creates bucket`() {
        val m = mutableMapOf<String, MutableMap<String, Float>>()
        BanditTuner.onTap(m, "shell", "a")
        assertEquals(BanditTuner.STEP, m["shell"]?.get("a") ?: -1f, 0.0001f)
    }

    @Test
    fun `tap caps at max`() {
        val m = mutableMapOf("shell" to mutableMapOf("a" to 0.95f))
        repeat(3) { BanditTuner.onTap(m, "shell", "a") }
        assertEquals(BanditTuner.CAP, m["shell"]?.get("a") ?: -1f, 0.0001f)
    }

    @Test
    fun `tap decays others in same state only`() {
        val m = mutableMapOf("shell" to mutableMapOf("a" to 1f, "b" to 0.5f), "vim" to mutableMapOf("c" to 1f))
        BanditTuner.onTap(m, "shell", "a")
        assertEquals(0.5f * BanditTuner.DECAY, m["shell"]?.get("b") ?: -1f, 0.0001f)
        assertEquals(1f, m["vim"]?.get("c") ?: -1f, 0.0001f)
    }

    @Test
    fun `repeated taps converge tapped to top`() {
        val m = mutableMapOf<String, MutableMap<String, Float>>()
        repeat(5) { BanditTuner.onTap(m, "shell", "b") }
        val wb = BanditTuner.weightOf(m, "shell", "b")
        assertTrue(wb > BanditTuner.weightOf(m, "shell", "a"))
        assertTrue(BanditTuner.winsTie(wb, 0f, 1L, 2L))
    }

    @Test
    fun `higher weight wins regardless of recency`() {
        assertTrue(BanditTuner.winsTie(0.3f, 0.1f, 100L, 999L))
        assertFalse(BanditTuner.winsTie(0.1f, 0.3f, 999L, 100L))
    }

    @Test
    fun `equal weights fall back to recency`() {
        assertTrue(BanditTuner.winsTie(0f, 0f, 200L, 100L))
        assertFalse(BanditTuner.winsTie(0f, 0f, 100L, 200L))
        assertFalse(BanditTuner.winsTie(0f, 0f, 100L, 100L))
    }
}
