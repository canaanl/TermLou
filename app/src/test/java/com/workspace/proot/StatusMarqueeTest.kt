package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态栏长文本纵向滚动（5.9.22）的判据锁。
 *
 * 需求：栏高锁死 1 行，超长时整行往上走；显示时间原来是 1000ms，
 * 走不完就延长到走完一遍为止（不要上限）。
 */
class StatusMarqueeTest {

    @Test
    fun `一行以内不延长时间`() {
        assertEquals(0L, StatusMarquee.passMs(0))
        assertEquals(0L, StatusMarquee.passMs(1))
        assertEquals(1000L, StatusMarquee.showMs(1))
        assertEquals(1000L, StatusMarquee.showMs(1, 1000L))
    }

    @Test
    fun `走完一遍等于每行停留加过渡`() {
        // 3 行：3×450 + 2×250 = 1850 → 延长到 1850
        assertEquals(1850L, StatusMarquee.passMs(3))
        assertEquals(1850L, StatusMarquee.showMs(3))
        // 2 行：2×450 + 1×250 = 1150 → 走不完 1000 也不行，延长到 1150
        assertEquals(1150L, StatusMarquee.passMs(2))
        assertEquals(1150L, StatusMarquee.showMs(2))
    }

    @Test
    fun `极端长名延长到走完一次没有上限`() {
        // 50 行：50×450 + 49×250 = 34750ms。用户明确不要上限。
        assertEquals(34750L, StatusMarquee.showMs(50))
    }

    private val tops3 = intArrayOf(0, 40, 80)

    @Test
    fun `起始停在第一行`() {
        assertEquals(0, StatusMarquee.scrollYAt(0L, tops3))
        assertEquals(0, StatusMarquee.scrollYAt(449L, tops3))
    }

    @Test
    fun `停留满后过渡到下一行`() {
        // 第 1 行停 0..450，第 450..700 走到第 2 行
        assertEquals(40, StatusMarquee.scrollYAt(700L, tops3))
        assertEquals(40, StatusMarquee.scrollYAt(900L, tops3))
        // 第 2 行：900..1350 停，1350..1600 走到第 3 行
        assertEquals(80, StatusMarquee.scrollYAt(1600L, tops3))
    }

    @Test
    fun `过渡中是线性插值`() {
        // 450..700 从 0 走到 40：中点 575 应在 20
        assertEquals(20, StatusMarquee.scrollYAt(575L, tops3))
    }

    @Test
    fun `最后一行停满剩余时间到达点停在底`() {
        assertEquals(80, StatusMarquee.scrollYAt(1600L, tops3))
        assertEquals(80, StatusMarquee.scrollYAt(1850L, tops3))
        assertEquals(80, StatusMarquee.scrollYAt(99999L, tops3))
    }

    @Test
    fun `行顶不均匀时照样对齐`() {
        // 实际排版行高可能有零点几像素误差：目标直接取实测行顶，不做乘法
        val uneven = intArrayOf(0, 41, 81)
        assertEquals(0, StatusMarquee.scrollYAt(0L, uneven))
        assertEquals(41, StatusMarquee.scrollYAt(700L, uneven))
        assertEquals(81, StatusMarquee.scrollYAt(1600L, uneven))
    }

    @Test
    fun `单行和空数组永远是零`() {
        assertEquals(0, StatusMarquee.scrollYAt(500L, intArrayOf(0)))
        assertEquals(0, StatusMarquee.scrollYAt(500L, intArrayOf()))
        assertEquals(0, StatusMarquee.scrollYAt(-5L, tops3))
    }

    @Test
    fun `基础时长可配`() {
        assertEquals(2000L, StatusMarquee.showMs(1, 2000L))
        assertEquals(2000L, StatusMarquee.showMs(2, 2000L))
    }

    @Test
    fun `常量与原来一致`() {
        assertEquals("原来就是 1000ms，不能动", 1000L, StatusMarquee.BASE_MS)
        assertTrue(StatusMarquee.HOLD_MS > 0)
        assertTrue(StatusMarquee.STEP_MS > 0)
    }
}
