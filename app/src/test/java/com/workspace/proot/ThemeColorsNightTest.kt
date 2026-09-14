package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorsNightTest {

    @Test
    fun `default without args keeps night mapping`() {
        assertEquals(ThemeColors.default(true), ThemeColors.default())
    }

    @Test
    fun `day surface is lighter than night surface`() {
        assertTrue(ThemeColors.default(false).surface > ThemeColors.default(true).surface)
    }

    @Test
    fun `day onSurface is darker than night onSurface`() {
        assertTrue(ThemeColors.default(false).onSurface < ThemeColors.default(true).onSurface)
    }

    @Test
    fun `brand seed stays primary in both modes`() {
        assertEquals(ThemeColors.SEED, ThemeColors.default(false).primary)
        assertEquals(ThemeColors.SEED, ThemeColors.default(true).primary)
    }
}
