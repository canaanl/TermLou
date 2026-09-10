package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRecommenderTest {

    private val now = 1_000_000_000L

    private fun score(
        id: String,
        stateUsage: Map<String, Int>?,
        stateSeq: List<String>,
        anchor: List<String>,
        usage: Map<String, Map<String, Int>>,
        lastUsed: Map<String, Long> = emptyMap()
    ): Float = CommandRecommender.summonScore(id, stateUsage, stateSeq, anchor, usage, lastUsed, now)

    @Test
    fun `never-used command scores zero`() {
        val s = score("n", null, emptyList(), emptyList(), emptyMap())
        assertEquals(0f, s, 0f)
    }

    @Test
    fun `global cumulative covers cold state`() {
        val usage = mapOf("shell" to mapOf("a" to 10), "vim" to mapOf("b" to 2))
        val sB = score("b", null, emptyList(), emptyList(), usage)
        val sA = score("a", null, emptyList(), emptyList(), usage)
        assertEquals(0.16f, sB, 0.001f)
        assertTrue(sB > 0f)
        assertTrue(sB < sA)
    }

    @Test
    fun `successor factor wins with evidence`() {
        val usage = mapOf("shell" to mapOf("hot" to 20, "next" to 1))
        val seq = List(10) { listOf("x", "next") }.flatten()
        val sNext = score("next", usage["shell"], seq, listOf("x"), usage)
        val sHot = score("hot", usage["shell"], seq, listOf("x"), usage)
        assertTrue("successor should outrank pure freq: $sNext vs $sHot", sNext > sHot)
    }

@Test
    fun `successor term is zero without anchor`() {
        val usage = mapOf("shell" to mapOf("next" to 1))
        val seq = List(5) { listOf("x", "next") }.flatten()
        val withAnchor = score("next", usage["shell"], seq, listOf("x"), usage)
        val noAnchor = score("next", usage["shell"], seq, emptyList(), usage)
        assertTrue(withAnchor > noAnchor)
    }

    @Test
    fun `successor term is zero when anchor absent in sequence`() {
        val usage = mapOf("shell" to mapOf("b" to 1))
        val seq = listOf("y", "z")
        val s = score("b", usage["shell"], seq, listOf("x"), usage)
        val expected = CommandRecommender.W_STATE * 1.0f + CommandRecommender.W_GLOBAL * 0.5f
        assertEquals(expected, s, 0.0001f)
    }

    @Test
    fun `missing state freq contributes zero`() {
        val usage = mapOf("shell" to mapOf("a" to 5))
        val sA = score("a", emptyMap(), emptyList(), emptyList(), usage)
        assertEquals(CommandRecommender.W_GLOBAL * 1.0f, sA, 0.0001f)
    }

    @Test
    fun `future lastUsed is clamped to zero recency`() {
        val usage = emptyMap<String, Map<String, Int>>()
        val stateUsage = mapOf("a" to 1)
        val s = CommandRecommender.summonScore("a", stateUsage, emptyList(), emptyList(), usage, mapOf("a" to now + 5000L), now)
        val expected = CommandRecommender.W_STATE * 1.0f
        assertEquals(expected, s, 0.0001f)
    }

    @Test
    fun `recent use boosts recency factor`() {
        val usage = mapOf("shell" to mapOf("a" to 1, "b" to 1))
        val recent = score("a", usage["shell"], emptyList(), emptyList(), usage, mapOf("a" to now - 1000L))
        val stale = score("a", usage["shell"], emptyList(), emptyList(), usage, mapOf("a" to now - 30L * 24 * 60 * 60 * 1000))
        assertTrue(recent > stale)
    }

    @Test
    fun `multi-order successor outranks single-order`() {
        // A,B→C appears 5 times (strong 2-order signal)
        // A→D appears 5 times but A,B→D never
        val seq = mutableListOf<String>()
        repeat(5) { seq.addAll(listOf("A", "B", "C")) }
        repeat(5) { seq.addAll(listOf("A", "D")) }
        val usage = mapOf("s" to mapOf("C" to 5, "D" to 5))
        // With 2-order context [A, B], C should score higher than D
        val sC = score("C", usage["s"], seq, listOf("A", "B"), usage)
        val sD = score("D", usage["s"], seq, listOf("A", "B"), usage)
        assertTrue("2-order C=$sC should outrank 1-order D=$sD", sC > sD)
    }

    @Test
    fun `falls back to lower order when insufficient data`() {
        // A,B appears only 1 time (< MIN_TRANSITIONS=3), so 2-order returns 0
        // A→E appears 10 times, so 1-order works
        val seq = mutableListOf<String>()
        seq.addAll(listOf("A", "B", "X")) // 1 occurrence of A,B
        repeat(10) { seq.addAll(listOf("A", "E")) }
        val usage = mapOf("s" to mapOf("E" to 10, "X" to 1))
        val sE = score("E", usage["s"], seq, listOf("A", "B"), usage)
        assertTrue("should fallback to 1-order and E>0: $sE", sE > 0f)
    }

    @Test
    fun `3-order match beats 2-order when both sufficient`() {
        // A,B,C→D appears 5 times
        // A,B→E appears 5 times but A,B,C→E never
        val seq = mutableListOf<String>()
        repeat(5) { seq.addAll(listOf("A", "B", "C", "D")) }
        repeat(5) { seq.addAll(listOf("A", "B", "E")) }
        val usage = mapOf("s" to mapOf("D" to 5, "E" to 5))
        val sD = score("D", usage["s"], seq, listOf("A", "B", "C"), usage)
        val sE = score("E", usage["s"], seq, listOf("A", "B", "C"), usage)
        assertTrue("3-order D=$sD should beat 2-order E=$sE", sD > sE)
    }
}
