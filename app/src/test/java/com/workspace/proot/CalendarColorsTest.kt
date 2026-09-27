package com.workspace.proot

import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日历格子配色锁定：「有笔记」的两档填充色必须保证——
 * 日期数字 ≥7:1（正文级），12dp 状态图形 ≥2.5:1（图形级，靠形状+颜色双编码补偿）。
 * 改 CalendarColors 里的色值会被这里的对比度断言拦下。
 */
class CalendarColorsTest {

    /** WCAG 相对亮度。 */
    private fun luminance(color: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun `浅色档填充 日期数字对比度不低于 7比1`() {
        val theme = ThemeColors.default(night = false)
        val ratio = contrast(CalendarColors.noteFill(night = false), theme.onSurface)
        assertTrue("浅色档填充 vs 数字 = %.2f:1".format(ratio), ratio >= 7.0)
    }

    @Test
    fun `夜间档填充 日期数字对比度不低于 7比1`() {
        val theme = ThemeColors.default(night = true)
        val ratio = contrast(CalendarColors.noteFill(night = true), theme.onSurface)
        assertTrue("夜间档填充 vs 数字 = %.2f:1".format(ratio), ratio >= 7.0)
    }

    @Test
    fun `两档填充上 四种状态图形对比度不低于 2点5比1`() {
        val icons = listOf(
            CalendarColors.TODO_OPEN,
            CalendarColors.TODO_PARTIAL,
            CalendarColors.TODO_DONE,
            CalendarColors.TAG_GRAY
        )
        for (night in listOf(false, true)) {
            val fill = CalendarColors.noteFill(night)
            for (icon in icons) {
                val ratio = contrast(fill, icon)
                assertTrue(
                    "night=%s 填充 0x%06X vs 图形 0x%06X = %.2f:1"
                        .format(night, fill and 0xFFFFFF, icon and 0xFFFFFF, ratio),
                    ratio >= 2.5
                )
            }
        }
    }

    @Test
    fun `填充色与页面底色拉得开 填充格能被看出来`() {
        for (night in listOf(false, true)) {
            val theme = ThemeColors.default(night)
            val ratio = contrast(CalendarColors.noteFill(night), theme.surface)
            assertTrue("night=%s 填充 vs surface = %.2f:1".format(night, ratio), ratio >= 1.15)
        }
    }
}
