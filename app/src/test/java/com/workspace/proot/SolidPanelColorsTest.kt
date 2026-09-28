package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉拟物光学「对比度反解」的可见性锁。
 *
 * 背景色是用户可自定义的（调色盘），固定比例的配色方案换种底色就会退化成看不见的
 * 阴影——这里把"任意背景下光学关系恒成立"锁死：主题日/夜两档 + 纯黑/纯白/中灰/
 * 极端彩。凸起/凹陷都成对给出两个方向，所以纯黑底（朝黑物理上不存在）或纯白底
 * （朝白不存在）的退化情形由"至少一个方向可见"兜住。
 */
class SolidPanelColorsTest {

    private val paletteBackgrounds = listOf(
        0xFF000000.toInt(), // 纯黑：朝黑的暗边方向物理上不存在
        0xFFFFFFFF.toInt(), // 纯白：朝白的高光方向物理上不存在
        0xFF808080.toInt(), // 中间调灰：两个方向都弱，最容易被固定比例方案漏掉
        0xFFFF0000.toInt(),
        0xFF00FF00.toInt(),
        0xFF0000FF.toInt(),
        0xFFFFFF00.toInt()
    )

    private fun cases(): List<Pair<String, SolidColors>> = buildList {
        add("night" to SolidPanel.of(ThemeColors.default(night = true)))
        add("day" to SolidPanel.of(ThemeColors.default(night = false)))
        for (bg in paletteBackgrounds) {
            add("#%06X".format(bg and 0xFFFFFF) to SolidPanel.of(
                ThemeColors.default(palette = PaletteSpec(bg, ThemeColors.SEED))
            ))
        }
    }

    /**
     * 某个方向的可见性有物理上限：朝黑混最多到 (L+0.05)/0.05，朝白混最多到 1.05/(L+0.05)。
     * 达不到目标阈值时 [ColorMath.mixUntil] 必须退到该方向的极端值（= 该方向能给出的
     * 最强对比），而不是停在某个不上不下的中间色。
     */
    private fun assertToward(name: String, base: Int, target: Int, tone: Int, minContrast: Double) {
        val best = ColorMath.contrast(target, base)
        if (best >= minContrast) {
            assertTrue(
                "$name 未达下限 $minContrast（该方向上限 $best）",
                ColorMath.contrast(tone, base) >= minContrast
            )
        } else {
            assertEquals("$name 该方向物理上限不足，应取极端值", target, tone)
        }
    }

    @Test
    fun everyOpticalToneIsOpaque() {
        for ((name, c) in cases()) {
            val tones = listOf(
                "panel" to c.panel,
                "grip" to c.grip, "ink" to c.ink, "key" to c.key, "keyHi" to c.keyHi,
                "keyShadow" to c.keyShadow, "keyCast" to c.keyCast,
                "keyPressed" to c.keyPressed, "keyPressedInner" to c.keyPressedInner,
                "keyPressedHi" to c.keyPressedHi
            )
            for ((field, color) in tones) {
                assertEquals("$name/$field 必须是实色", 0xFF, (color ushr 24) and 0xFF)
            }
        }
    }

    @Test
    fun keyFaceSeparatesFromPanel() {
        for ((name, c) in cases()) {
            assertTrue(
                "$name 键面与面板不可区分",
                ColorMath.contrast(c.key, c.panel) >= 1.03
            )
        }
    }

    @Test
    fun raisedKeyHasVisibleDirectionAndCorrectPolarity() {
        for ((name, c) in cases()) {
            val hi = ColorMath.contrast(c.keyHi, c.key)
            val shadow = ColorMath.contrast(c.keyShadow, c.key)
            assertTrue("$name 凸起键至少一个方向可见", maxOf(hi, shadow) >= 1.25)
            if (hi > 1.0) {
                assertTrue(
                    "$name 凸起上棱高光必须更亮",
                    ColorMath.relativeLuminance(c.keyHi) > ColorMath.relativeLuminance(c.key)
                )
            }
            if (shadow > 1.0) {
                assertTrue(
                    "$name 凸起下棱暗边必须更暗",
                    ColorMath.relativeLuminance(c.keyShadow) < ColorMath.relativeLuminance(c.key)
                )
            }
        }
    }

    @Test
    fun pressedKeyHasVisibleDirectionAndInvertsPolarity() {
        for ((name, c) in cases()) {
            val inner = ColorMath.contrast(c.keyPressedInner, c.key)
            val hi = ColorMath.contrast(c.keyPressedHi, c.key)
            assertTrue("$name 凹陷键至少一个方向可见", maxOf(inner, hi) >= 1.20)
            if (inner > 1.0) {
                assertTrue(
                    "$name 凹陷上棱必须是内阴影（比键面暗）",
                    ColorMath.relativeLuminance(c.keyPressedInner) < ColorMath.relativeLuminance(c.key)
                )
            }
            if (hi > 1.0) {
                assertTrue(
                    "$name 凹陷下棱高光必须比键面亮",
                    ColorMath.relativeLuminance(c.keyPressedHi) > ColorMath.relativeLuminance(c.key)
                )
            }
        }
    }

    @Test
    fun pressedKeyIsDarkerThanRest() {
        for ((name, c) in cases()) {
            assertTrue(
                "$name 按下必须比静止更沉",
                ColorMath.relativeLuminance(c.keyPressed) < ColorMath.relativeLuminance(c.key)
            )
        }
    }

    @Test
    fun rowTextStaysReadableOnAnyBackground() {
        for ((name, c) in cases()) {
            assertTrue(
                "$name 行文字对比度不足",
                ColorMath.contrast(c.ink, c.panel) >= 4.5
            )
        }
    }

    @Test
    fun mixUntilDegradesWhenDirectionIsPhysicallyImpossible() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(black, ColorMath.mixUntil(black, black, 1.5))
        assertEquals(white, ColorMath.mixUntil(white, white, 1.5))
    }

    @Test
    fun mixUntilAlwaysLandsOnTheMostVisibleToneAvailable() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val bases = listOf(
            0xFF000000.toInt(), 0xFF1E1E1E.toInt(), 0xFF242426.toInt(),
            0xFF808080.toInt(), 0xFFF3EDF7.toInt(), 0xFFFFFFFF.toInt()
        )
        for (base in bases) {
            for (target in listOf(white, black)) {
                for (minC in listOf(1.10, 1.30, 1.45, 1.55, 2.00)) {
                    assertToward(
                        "base=$base→${if (target == white) "W" else "B"}@$minC",
                        base, target, ColorMath.mixUntil(base, target, minC), minC
                    )
                }
            }
        }
    }
}
