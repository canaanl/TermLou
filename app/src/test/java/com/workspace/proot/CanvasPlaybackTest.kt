package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("MagicNumber")
class CanvasPlaybackTest {

    private fun anim(
        durationMs: Int = 600,
        delayMs: Int = 0,
        repeat: Int = 1,
        staggerMs: Int = 0,
        targets: List<String> = emptyList()
    ) = CanvasAnimation(
        target = if (targets.isEmpty()) "a" else "",
        targets = targets,
        durationMs = durationMs,
        delayMs = delayMs,
        repeat = repeat,
        staggerMs = staggerMs
    )

    @Test
    fun `empty timeline has no end and no infinite loop`() {
        assertEquals(0L, CanvasPlayback.finiteEndMs(emptyList()))
        assertFalse(CanvasPlayback.hasInfinite(emptyList()))
    }

    @Test
    fun `finite end is delay plus duration times repeats`() {
        val timeline = listOf(
            anim(durationMs = 600, delayMs = 100, repeat = 2),
            anim(durationMs = 300, delayMs = 0, repeat = 1)
        )
        assertEquals(1_300L, CanvasPlayback.finiteEndMs(timeline))
        assertFalse(CanvasPlayback.hasInfinite(timeline))
    }

    @Test
    fun `multi target stagger is included in the real end`() {
        val timeline = listOf(
            anim(durationMs = 500, delayMs = 200, staggerMs = 100, targets = listOf("a", "b", "c"))
        )
        assertEquals(900L, CanvasPlayback.finiteEndMs(timeline))
    }

    @Test
    fun `infinite animations are reported but never drive the seek end`() {
        val timeline = listOf(
            anim(durationMs = 900, delayMs = 100, repeat = 0),
            anim(durationMs = 400, delayMs = 50, repeat = 2)
        )
        assertTrue(CanvasPlayback.hasInfinite(timeline))
        assertEquals(850L, CanvasPlayback.finiteEndMs(timeline))
    }

    @Test
    fun `infinite only timeline never invents an end`() {
        val timeline = listOf(anim(durationMs = 900, delayMs = 100, repeat = -1))
        assertTrue(CanvasPlayback.hasInfinite(timeline))
        assertEquals(0L, CanvasPlayback.finiteEndMs(timeline))
    }
}
