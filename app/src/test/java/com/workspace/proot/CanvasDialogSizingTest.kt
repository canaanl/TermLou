@file:Suppress("MagicNumber")

package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasDialogSizingTest {

    @Test
    fun `content height follows scene aspect ratio`() {
        val scene = CanvasScene(width = 360f, height = 220f)
        assertEquals(581, CanvasDialogSizing.contentHeightPx(scene, 950))
    }

    @Test
    fun `small scenes still have positive height`() {
        val scene = CanvasScene(width = 4000f, height = 32f)
        val height = CanvasDialogSizing.contentHeightPx(scene, 950)
        assertTrue(height > 0)
    }

    @Test
    fun `zero width is safe`() {
        val scene = CanvasScene(width = 360f, height = 220f)
        assertTrue(CanvasDialogSizing.contentHeightPx(scene, 0) > 0)
    }
}
