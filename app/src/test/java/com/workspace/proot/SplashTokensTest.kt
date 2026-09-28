package com.workspace.proot

import com.google.android.material.color.utilities.Hct
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
    fun `bandToLevel normalizes to full range`() {
        assertEquals(0, SplashTokens.bandToLevel(0, 2))
        assertEquals(19, SplashTokens.bandToLevel(1, 2))
        assertEquals(0, SplashTokens.bandToLevel(0, 6))
        assertEquals(19, SplashTokens.bandToLevel(5, 6))
        assertEquals(7, SplashTokens.bandToLevel(2, 6))
        assertEquals(19, SplashTokens.bandToLevel(0, 1))
    }

    @Test
    fun `invertLevel mirrors full range`() {
        assertEquals(19, SplashTokens.invertLevel(0))
        assertEquals(0, SplashTokens.invertLevel(19))
        assertEquals(10, SplashTokens.invertLevel(9))
    }

    @Test
    fun `percentileThresholds splits even populations`() {
        val hist = IntArray(256)
        for (i in 0 until 60) hist[i] = 10
        for (i in 60 until 120) hist[i] = 10
        for (i in 120 until 180) hist[i] = 10
        for (i in 180 until 256) hist[i] = 10
        val total = 256 * 10
        val t = SplashTokens.percentileThresholds(hist, total, 6)
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
        assertEquals(5, SplashTokens.percentileThresholds(IntArray(256), 0, 6).size)
        val single = IntArray(256)
        single[128] = 100
        val t = SplashTokens.percentileThresholds(single, 100, 6)
        assertEquals(5, t.size)
        for (v in t) assertTrue(v in 0..255)
    }

    @Test
    fun `level alphas span full range monotonic`() {
        val a = SplashTokens.LEVEL_ALPHAS
        assertEquals(20, a.size)
        assertEquals(0, a[0])
        assertEquals(255, a[19])
        for (i in 1 until a.size) assertTrue(a[i] >= a[i - 1])
    }

    @Test
    fun `cellColor honours passed gradient endpoints`() {
        val first = 0xFF112233.toInt()
        val second = 0xFF445566.toInt()
        assertEquals(first, SplashTokens.cellColor(0, first, second))
        assertEquals(second, SplashTokens.cellColor(SplashTokens.COLS - 1, first, second))
    }

    @Test
    fun `gradient pair off keeps brand constants`() {
        val (a, b) = SplashTokens.gradientPair(ThemeColors.default(night = true))
        assertEquals(UiTokens.primaryGreen, a)
        assertEquals(UiTokens.tertiaryBlue, b)
        val (c, d) = SplashTokens.gradientPair(ThemeColors.default(night = false))
        assertEquals(UiTokens.primaryGreen, c)
        assertEquals(UiTokens.tertiaryBlue, d)
    }

    @Test
    fun `gradient pair on derives second color from theme green`() {
        val seed = 0xFF3A7D44.toInt()
        val t = ThemeColors.default(night = true, palette = PaletteSpec(0xFF101418.toInt(), seed))
        val (a, b) = SplashTokens.gradientPair(t)
        assertEquals(seed, a)
        val ha = Hct.fromInt(a)
        val hb = Hct.fromInt(b)
        // 第二色 = 主题绿 HCT 色相 +60°（同彩度同明度请求）；sRGB 色域裁剪只许降彩度，
        // 色相与明度必须保持（round-trip 允许 1~1.5 单位量化误差）。
        assertEquals((ha.hue + 60.0) % 360.0, hb.hue, 1.0)
        assertEquals(ha.tone, hb.tone, 1.5)
        assertTrue("彩度只降不漂: ${ha.chroma} -> ${hb.chroma}", hb.chroma <= ha.chroma + 0.5)
        assertTrue("彩度不塌灰: ${ha.chroma} -> ${hb.chroma}", hb.chroma > ha.chroma * 0.5)
    }
}
