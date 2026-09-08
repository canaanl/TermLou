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
        anchor: String?,
        usage: Map<String, Map<String, Int>>,
        lastUsed: Map<String, Long> = emptyMap()
    ): Float = CommandRecommender.summonScore(id, stateUsage, stateSeq, anchor, usage, lastUsed, now)

    @Test
    fun `never-used command scores zero`() {
        val s = score("n", null, emptyList(), null, emptyMap())
        assertEquals(0f, s, 0f)
    }

    @Test
    fun `global cumulative covers cold state`() {
        val usage = mapOf("shell" to mapOf("a" to 10), "vim" to mapOf("b" to 2))
        val sB = score("b", null, emptyList(), null, usage)
        val sA = score("a", null, emptyList(), null, usage)
        assertEquals(0.16f, sB, 0.001f)
        assertTrue(sB > 0f)
        assertTrue(sB < sA)
    }

    @Test
    fun `successor factor wins with evidence`() {
        val usage = mapOf("shell" to mapOf("hot" to 20, "next" to 1))
        val seq = List(10) { listOf("x", "next") }.flatten()
        val sNext = score("next", usage["shell"], seq, "x", usage)
        val sHot = score("hot", usage["shell"], seq, "x", usage)
        assertTrue("successor should outrank pure freq: $sNext vs $sHot", sNext > sHot)
    }

    @Test
    fun `successor term is zero without anchor`() {
        val usage = mapOf("shell" to mapOf("next" to 1))
        val seq = listOf("x", "next", "x", "next")
        val withAnchor = score("next", usage["shell"], seq, "x", usage)
        val noAnchor = score("next", usage["shell"], seq, null, usage)
        assertTrue(withAnchor > noAnchor)
    }

    @Test
    fun `successor term is zero when anchor absent in sequence`() {
        val usage = mapOf("shell" to mapOf("b" to 1))
        val seq = listOf("y", "z")
        val s = score("b", usage["shell"], seq, "x", usage)
        val expected = CommandRecommender.W_STATE * 1.0f + CommandRecommender.W_GLOBAL * 0.5f
        assertEquals(expected, s, 0.0001f)
    }

    @Test
    fun `missing state freq contributes zero`() {
        val usage = mapOf("shell" to mapOf("a" to 5))
        val sA = score("a", emptyMap(), emptyList(), null, usage)
        assertEquals(CommandRecommender.W_GLOBAL * 1.0f, sA, 0.0001f)
    }

    @Test
    fun `future lastUsed is clamped to zero recency`() {
        val usage = emptyMap<String, Map<String, Int>>()
        val stateUsage = mapOf("a" to 1)
        val s = CommandRecommender.summonScore("a", stateUsage, emptyList(), null, usage, mapOf("a" to now + 5000L), now)
        val expected = CommandRecommender.W_STATE * 1.0f
        assertEquals(expected, s, 0.0001f)
    }

    @Test
    fun `recent use boosts recency factor`() {
        val usage = mapOf("shell" to mapOf("a" to 1, "b" to 1))
        val recent = score("a", usage["shell"], emptyList(), null, usage, mapOf("a" to now - 1000L))
        val stale = score("a", usage["shell"], emptyList(), null, usage, mapOf("a" to now - 30L * 24 * 60 * 60 * 1000))
        assertTrue(recent > stale)
    }
}