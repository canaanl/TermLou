@file:Suppress("MagicNumber")

package com.workspace.proot

import org.junit.Assert.assertTrue
import org.junit.Test

/** 禁用态图标色必须随背景推导：看得见、但明显弱于启用态。 */
class DisabledIconColorTest {

    private val themes = listOf(
        ThemeColors.default(true),
        ThemeColors.default(false),
        ThemeColors.default(true, PaletteSpec(0xFF000000.toInt(), 0xFF00C853.toInt())),
        ThemeColors.default(false, PaletteSpec(0xFFFFFFFF.toInt(), 0xFFB00020.toInt())),
        ThemeColors.default(true, PaletteSpec(0xFF777777.toInt(), 0xFF3F51B5.toInt())),
        ThemeColors.default(false, PaletteSpec(0xFFF5E6C8.toInt(), 0xFF2D7D46.toInt())),
    )

    @Test
    fun `disabled color stays visible but muted on every palette`() {
        for (theme in themes) {
            val disabled = ColorMath.disabledTint(theme.surface, theme.onSurfaceVariant)
            val contrast = ColorMath.contrast(disabled, theme.surface)
            assertTrue(
                "disabled color must be visible on ${ColorMath.hex(theme.surface)}: $contrast",
                contrast >= 1.95,
            )
            assertTrue(
                "disabled color must stay muted on ${ColorMath.hex(theme.surface)}: $contrast",
                contrast <= 2.25,
            )
        }
    }

    @Test
    fun `disabled color is distinguishable from the enabled brand color`() {
        for (theme in themes) {
            val disabled = ColorMath.disabledTint(theme.surface, theme.onSurfaceVariant)
            assertTrue(
                "disabled ${ColorMath.hex(disabled)} vs enabled ${ColorMath.hex(theme.primaryVisible)}",
                disabled != theme.primaryVisible,
            )
        }
    }

    @Test
    fun `degenerate neutral still falls back to a visible tone`() {
        val face = 0xFF777777.toInt()
        val disabled = ColorMath.disabledTint(face, face)
        assertTrue(ColorMath.contrast(disabled, face) >= 1.95)
    }
}
