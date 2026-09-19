package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashTokensTest {

    @Test
    fun `quantizeBands maps dark to bright across thresholds`() {
        val t = intArrayOf(40, 80, 120, 160, 200)
        assertEquals(5, SplashTokens.quantizeBands(10, t))
        assertEquals(4, SplashTokens.quantizeBands(60, t))
        assertEquals(3, SplashTokens.quantizeBands(100, t))
        assertEquals(2, SplashTokens.quantizeBands(140, t))
        assertEquals(1, SplashTokens.quantizeBands(190, t))
        assertEquals(0, SplashTokens.quantizeBands(220, t))
        assertEquals(4, SplashTokens.quantizeBands(40, t))
    }

    @Test
    fun `invertLevel mirrors six levels`() {
        assertEquals(5, SplashTokens.invertLevel(0))
        assertEquals(0, SplashTokens.invertLevel(5))
        assertEquals(3, SplashTokens.invertLevel(2))
    }

    @Test
    fun `percentileThresholds splits even populations`() {
        val hist = IntArray(256)
        for (i in 0 until 60) hist[i] = 10
        for (i in 60 until 120) hist[i] = 10
        for (i in 120 until 180) hist[i] = 10
        for (i in 180 until 256) hist[i] = 10
        val total = 256 * 10
        val t = SplashTokens.percentileThresholds(hist, total)
        assertEquals(5, t.size)
        for (i in 0 until t.size) {
            assertTrue(t[i] in 0..255)
            if (i > 0) assertTrue(t[i] >= t[i - 1])
        }
        assertTrue(t[0] in 30..70)
        assertTrue(t[4] in 190..230)
    }

    @Test
    fun `percentileThresholds handles degenerate input`() {
        assertEquals(5, SplashTokens.percentileThresholds(IntArray(256), 0).size)
        val single = IntArray(256)
        single[128] = 100
        val t = SplashTokens.percentileThresholds(single, 100)
        assertEquals(5, t.size)
        for (v in t) assertTrue(v in 0..255)
    }

    @Test
    fun `level alphas are non-decreasing full opaque`() {
        val a = SplashTokens.LEVEL_ALPHAS
        assertEquals(6, a.size)
        assertEquals(0, a[0])
        assertEquals(255, a[5])
        for (i in 1 until a.size) assertTrue(a[i] >= a[i - 1])
    }
}
