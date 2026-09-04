package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UiTokensTest {

    @Test
    fun `typography tiers have expected sp values`() {
        assertEquals(18f, UiTokens.TEXT_TITLE)
        assertEquals(15f, UiTokens.TEXT_BODY)
        assertEquals(13f, UiTokens.TEXT_COMPACT)
        assertEquals(12f, UiTokens.TEXT_META)
    }

    @Test
    fun `brand splash tokens keep their literals`() {
        assertEquals(0xFF2D7D46.toInt(), UiTokens.primaryGreen)
        assertEquals(0xFF0E639C.toInt(), UiTokens.tertiaryBlue)
    }

    @Test
    fun `generated palette follows hybrid material3 rules`() {
        val t = ThemeColors.default()
        assertEquals(0xFF2D7D46.toInt(), ThemeColors.SEED)
        assertEquals(0xFF2D7D46.toInt(), t.primary)
        assertEquals(t.outline, t.outlineVariant)
        assertEquals(t.surfaceTint, t.primary)
        assertNotEquals(t.surface, t.surfaceVariant)
        assertNotEquals(t.onSurface, t.onSurfaceVariant)
        assertNotEquals(t.primary, t.primaryContainer)
        assertNotEquals(t.primaryContainer, t.surfaceVariant)
    }

    @Test
    fun `decorative colors preserve original literals bit by bit`() {
        assertEquals(0xFF00FF41.toInt(), UiTokens.statusGreen)
        assertEquals(0xFF4DD0E1.toInt(), UiTokens.linkCyan)
        assertEquals(0x80FFD54F.toInt(), UiTokens.dirRowBg)
        assertEquals(0xFFFFD54F.toInt(), UiTokens.amber)
        assertEquals(0xF0FFFFFF.toInt(), UiTokens.braceText)
        assertEquals(0x661E1E1E.toInt(), UiTokens.chipSurface)
        assertEquals(0xFF64B5F6.toInt(), UiTokens.braceBlue)
        assertEquals(0xFFFF5252.toInt(), UiTokens.braceRed)
        assertEquals(0x99000000.toInt(), UiTokens.scrimStart)
        assertEquals(0x33FFFFFF.toInt(), UiTokens.whiteFaint)
        assertEquals(0xFFA6A6A6.toInt(), UiTokens.splashText)
        assertEquals(0x1A0E639C.toInt(), UiTokens.backdropGlow)
        assertEquals(0x4D7FD8B0.toInt(), UiTokens.letterGlow)
        assertEquals(0x22FFFFFF.toInt(), UiTokens.searchBg)
        assertEquals(0xFFE67E22.toInt(), UiTokens.mergeOrange)
        assertEquals(0xFFCCCCCC.toInt(), UiTokens.totalText)
        assertEquals(0xFFB820262E.toInt(), UiTokens.tilePanelBg)
    }
}
