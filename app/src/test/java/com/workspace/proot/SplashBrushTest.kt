@file:Suppress("MagicNumber")

package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashBrushTest {

    @Test
    fun `line fills every cell along a horizontal stroke`() {
        val cells = mutableMapOf<Pair<Int, Int>, Int>()
        val changes = mutableMapOf<Pair<Int, Int>, Int?>()
        val stamps = SplashBrush.line(
            SplashBrush.Point(0.5f, 5.5f, 1f, 0L),
            SplashBrush.Point(20.5f, 5.5f, 1f, 100L),
            base = 0.8f,
            stylus = true
        )
        for (stamp in stamps) {
            SplashBrush.stamp(
                cells, stamp.x, stamp.y, stamp.radius,
                SplashTokens.ROWS, SplashTokens.COLS, changes
            )
        }
        for (c in 0..20) {
            assertTrue("missing cell $c in ${cells.keys}", cells.containsKey(5 to c))
        }
        assertTrue(changes.isNotEmpty())
    }

    @Test
    fun `stamp covers multiple cells for a thick brush`() {
        val cells = mutableMapOf<Pair<Int, Int>, Int>()
        SplashBrush.stamp(cells, 10.5f, 10.5f, 1.8f, SplashTokens.ROWS, SplashTokens.COLS)
        assertTrue(cells.containsKey(10 to 10))
        assertTrue(cells.containsKey(10 to 9))
        assertTrue(cells.containsKey(10 to 11))
        assertTrue(cells.containsKey(9 to 10))
        assertTrue(cells.containsKey(11 to 10))
        assertEquals(SplashTokens.LEVEL_FULL, cells[10 to 10])
    }

    @Test
    fun `stamp records old values only once and never dims`() {
        val cells = mutableMapOf<Pair<Int, Int>, Int>((10 to 10) to SplashTokens.LEVEL_FULL)
        val changes = mutableMapOf<Pair<Int, Int>, Int?>()
        SplashBrush.stamp(cells, 10.5f, 10.5f, 1.8f, SplashTokens.ROWS, SplashTokens.COLS, changes)
        SplashBrush.stamp(cells, 10.5f, 10.5f, 1.8f, SplashTokens.ROWS, SplashTokens.COLS, changes)
        assertEquals(SplashTokens.LEVEL_FULL, cells[10 to 10])
        assertTrue(!changes.containsKey(10 to 10))
        assertEquals(cells.size - 1, changes.size) // 只为新格子记录一次旧值
    }

    @Test
    fun `stylus pressure raises radius and finger speed lowers it`() {
        val light = SplashBrush.radius(1.2f, 0.2f, 0f, stylus = true)
        val heavy = SplashBrush.radius(1.2f, 1f, 0f, stylus = true)
        assertTrue(heavy > light)

        val slow = SplashBrush.radius(1.2f, 1f, 0.01f, stylus = false)
        val fast = SplashBrush.radius(1.2f, 1f, 0.5f, stylus = false)
        assertTrue(slow > fast)
    }
}
