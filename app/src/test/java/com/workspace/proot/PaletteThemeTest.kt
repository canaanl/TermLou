package com.workspace.proot

import com.google.android.material.color.utilities.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调色盘推导规则锁定：字体/图标对任意背景 ≥4.5:1、容器阶梯随背景明暗换向、
 * 红/蓝色相可识别且明度档随背景走、关闭档与历史默认逐位一致。
 */
class PaletteThemeTest {

    private val backgrounds = listOf(
        0xFF000000.toInt(), 0xFF1E1E1E.toInt(), 0xFF3A1F14.toInt(),
        0xFF0B3D2E.toInt(), 0xFF808080.toInt(), 0xFFB0B0B0.toInt(),
        0xFFF5E6C8.toInt(), 0xFFE6EDF4.toInt(), 0xFFFFFFFF.toInt(),
        0xFF7FBF3F.toInt()
    )

    private val seeds = listOf(
        ThemeColors.SEED,
        0xFF90EE90.toInt(),
        0xFFFFFFFF.toInt(),
        0xFF000000.toInt(),
        0xFFB58200.toInt()
    )

    private fun palette(bg: Int, seed: Int = ThemeColors.SEED): ThemeColors =
        ThemeColors.default(night = true, palette = PaletteSpec(bg, seed))

    @Test
    fun paletteOffKeepsHistoricalBehavior() {
        assertEquals(ThemeColors.default(true), ThemeColors.default(true, null))
        assertEquals(ThemeColors.default(false), ThemeColors.default(false, null))
        assertFalse(ThemeColors.default(true).palette)
        assertTrue(palette(0xFF101010.toInt()).palette)
    }

    @Test
    fun surfaceIsExactlyTheCustomBackground() {
        for (bg in backgrounds) {
            assertEquals(bg, palette(bg).surface)
        }
    }

    @Test
    fun fontAndIconsReadableOnAnyBackground() {
        for (bg in backgrounds) {
            val t = palette(bg)
            assertTrue("onSurface vs ${ColorMath.hex(bg)}", ColorMath.contrast(t.onSurface, bg) >= 4.5)
            assertTrue("onSurfaceVariant vs ${ColorMath.hex(bg)}", ColorMath.contrast(t.onSurfaceVariant, bg) >= 4.5)
            // 字体落在卡片/输入框/容器档上同样要看得清
            assertTrue(ColorMath.contrast(t.onSurface, t.surfaceVariant) >= 4.5)
            assertTrue(ColorMath.contrast(t.onSurface, t.surfaceContainerLow) >= 4.5)
            assertTrue(ColorMath.contrast(t.onSurface, t.surfaceContainer) >= 4.5)
            assertTrue(ColorMath.contrast(t.onSurface, t.surfaceContainerHigh) >= 4.5)
            assertTrue(ColorMath.contrast(t.onSurface, t.surfaceContainerHighest) >= 4.5)
        }
    }

    @Test
    fun seedBecomesPrimaryWithReadableLabel() {
        for (seed in seeds) {
            for (bg in listOf(0xFF1E1E1E.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt())) {
                val t = ThemeColors.default(night = true, palette = PaletteSpec(bg, seed))
                assertEquals(seed, t.primary)
                assertTrue("onPrimary vs seed ${ColorMath.hex(seed)}", ColorMath.contrast(t.onPrimary, seed) >= 4.5)
            }
        }
    }

    @Test
    fun semanticHueRecognizableAndToneFollowsBackground() {
        val dark = palette(0xFF101010.toInt())
        val light = palette(0xFFF0F0F0.toInt())
        // 明度档随背景：深底亮档、浅底深档
        assertTrue(Hct.fromInt(dark.error).tone > Hct.fromInt(light.error).tone)
        assertTrue(Hct.fromInt(dark.tertiary).tone > Hct.fromInt(light.tertiary).tone)
        // 色相可识别：红还得是红、蓝还得是蓝
        val errHue = Hct.fromInt(dark.error).hue
        assertTrue("error hue=$errHue", errHue < 70.0 || errHue > 330.0)
        val tertHue = Hct.fromInt(dark.tertiary).hue
        assertTrue("tertiary hue=$tertHue", tertHue in 150.0..330.0)
    }

    @Test
    fun nightFlagFollowsBackgroundPolarity() {
        assertTrue(palette(0xFF000000.toInt()).night)
        assertFalse(palette(0xFFFFFFFF.toInt()).night)
        assertFalse(palette(0xFFE6EDF4.toInt()).night)
    }

    @Test
    fun containerLadderPolarity() {
        val dark = palette(0xFF1E1E1E.toInt())
        val dl = ColorMath::relativeLuminance
        assertTrue(dl(dark.surfaceContainerLowest) < dl(dark.surface))
        assertTrue(dl(dark.surfaceContainerHigh) > dl(dark.surface))
        assertTrue(dl(dark.surfaceContainerHighest) > dl(dark.surfaceContainerHigh))

        val light = palette(0xFFF0F0F0.toInt())
        val ll = ColorMath::relativeLuminance
        assertTrue(ll(light.surfaceContainerLowest) > ll(light.surface))
        assertTrue(ll(light.surfaceContainerHigh) < ll(light.surface))
        assertTrue(ll(light.surfaceContainerHighest) < ll(light.surfaceContainerHigh))
    }

    @Test
    fun inkCrossingKeepsBothSidesReadable() {
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.inkOf(0xFF1E1E1E.toInt()))
        assertEquals(0xFF000000.toInt(), ColorMath.inkOf(0xFFFFFFFF.toInt()))
        for (bg in backgrounds) {
            assertTrue(ColorMath.contrast(ColorMath.inkOf(bg), bg) >= 4.5)
        }
        // 中灰不能按位取反（会得到≈自身）
        assertTrue(ColorMath.contrast(0xFF808080.toInt(), 0xFF7F7F7F.toInt()) < 1.2)
    }

    @Test
    fun contrastAndMixSanity() {
        assertEquals(21.0, ColorMath.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, ColorMath.contrast(0xFF123456.toInt(), 0xFF123456.toInt()), 0.01)
        assertEquals(0xFF1E2B3C.toInt(), ColorMath.mix(0xFF1E2B3C.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.mix(0xFF1E2B3C.toInt(), 0xFFFFFFFF.toInt(), 1f))
    }

    @Test
    fun hexFormatsUppercaseWithoutAlpha() {
        assertEquals("#2D7D46", ColorMath.hex(0xFF2D7D46.toInt()))
        assertEquals("#FFFFFF", ColorMath.hex(0xFFFFFFFF.toInt()))
        assertEquals("#000000", ColorMath.hex(0xFF000000.toInt()))
    }
}
