package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashTokensTest {

    @Test
    fun `quantizeBlock maps dark mid bright`() {
        assertEquals(3, SplashTokens.quantizeBlock(10, 80, 170))
        assertEquals(2, SplashTokens.quantizeBlock(120, 80, 170))
        assertEquals(0, SplashTokens.quantizeBlock(200, 80, 170))
        assertEquals(2, SplashTokens.quantizeBlock(80, 80, 170))
        assertEquals(0, SplashTokens.quantizeBlock(170, 80, 170))
    }

    @Test
    fun `quantizeEdge maps strong weak none`() {
        assertEquals(3, SplashTokens.quantizeEdge(10f, 8f, 3f))
        assertEquals(1, SplashTokens.quantizeEdge(5f, 8f, 3f))
        assertEquals(0, SplashTokens.quantizeEdge(1f, 8f, 3f))
    }

    @Test
    fun `invertLevel mirrors levels`() {
        assertEquals(3, SplashTokens.invertLevel(0))
        assertEquals(2, SplashTokens.invertLevel(1))
        assertEquals(1, SplashTokens.invertLevel(2))
        assertEquals(0, SplashTokens.invertLevel(3))
    }

    @Test
    fun `otsu2 separates trimodal histogram`() {
        val hist = IntArray(256)
        for (i in 20..40) hist[i] = 50
        for (i in 110..130) hist[i] = 50
        for (i in 200..220) hist[i] = 50
        val (t1, t2) = SplashTokens.otsu2(hist, 21 * 50 * 3)
        assertTrue(t1 in 0..255)
        assertTrue(t2 in 0..255)
        assertTrue(t1 < t2)
        assertTrue(t1 in 35..115)
        assertTrue(t2 in 125..205)
    }

    @Test
    fun `otsu2 handles degenerate histogram`() {
        val hist = IntArray(256)
        hist[128] = 100
        val (t1, t2) = SplashTokens.otsu2(hist, 100)
        assertTrue(t1 in 0..255)
        assertTrue(t2 in 0..255)
        assertTrue(t1 < t2)
    }

    @Test
    fun `level alphas are non-decreasing full opaque`() {
        val a = SplashTokens.LEVEL_ALPHAS
        assertEquals(4, a.size)
        assertEquals(0, a[0])
        assertEquals(255, a[3])
        assertTrue(a[0] <= a[1] && a[1] <= a[2] && a[2] <= a[3])
    }
}
