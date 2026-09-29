package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空闲闪烁提示的避让规则锁定测试（5.9.1）。
 *
 * 手动点很难稳定复现（要在 10 分钟闲置窗口里对上闪烁的暗段，还要恰好有临时提示），
 * 所以规则抽成纯函数后逐条锁死。
 */
class WebNoticeArbiterTest {

    private fun inputs(
        serviceOn: Boolean = true,
        idleMs: Long = WebNoticeArbiter.IDLE_MS,
        temp: Boolean = false,
        ctrl: Boolean = false
    ) = WebNoticeArbiter.Inputs(
        serviceOn = serviceOn,
        idleMs = idleMs,
        tempStatusActive = temp,
        ctrlInfoVisible = ctrl
    )

    // ---------- 何时才闪：闲置满 10 分钟 ----------

    @Test
    fun `闲置刚过十分钟才闪`() {
        assertTrue(WebNoticeArbiter.shouldBlink(inputs(idleMs = WebNoticeArbiter.IDLE_MS)))
        assertTrue(WebNoticeArbiter.shouldBlink(inputs(idleMs = WebNoticeArbiter.IDLE_MS + 5_000)))
    }

    @Test
    fun `开着但一直在用就不闪`() {
        assertFalse(WebNoticeArbiter.shouldBlink(inputs(idleMs = 0)))
        assertFalse(WebNoticeArbiter.shouldBlink(inputs(idleMs = 60_000)))
        assertFalse(WebNoticeArbiter.shouldBlink(inputs(idleMs = WebNoticeArbiter.IDLE_MS - 1)))
    }

    @Test
    fun `服务关着不闪`() {
        assertFalse(
            WebNoticeArbiter.shouldBlink(
                inputs(serviceOn = false, idleMs = Long.MAX_VALUE)
            )
        )
    }

    @Test
    fun `有 2 秒临时提示时不闪`() {
        assertFalse(WebNoticeArbiter.shouldBlink(inputs(temp = true)))
    }

    @Test
    fun `按 Ctrl 显示按键信息时不闪`() {
        assertFalse(WebNoticeArbiter.shouldBlink(inputs(ctrl = true)))
    }

    @Test
    fun `按键信息与临时提示同时存在也不闪`() {
        assertFalse(WebNoticeArbiter.shouldBlink(inputs(temp = true, ctrl = true)))
    }

    @Test
    fun `闲置阈值就是十分钟`() {
        assertEquals(600_000L, WebNoticeArbiter.IDLE_MS)
    }

    // ---------- 延时检查：空闲等待期不排心跳 ----------

    @Test
    fun `没到点给出剩余延时`() {
        assertEquals(600_000L, WebNoticeArbiter.msUntilBlink(0))
        assertEquals(300_000L, WebNoticeArbiter.msUntilBlink(300_000))
    }

    @Test
    fun `已经到点剩余延时为 0`() {
        assertEquals(0L, WebNoticeArbiter.msUntilBlink(WebNoticeArbiter.IDLE_MS))
        assertEquals(0L, WebNoticeArbiter.msUntilBlink(WebNoticeArbiter.IDLE_MS + 10_000))
        assertEquals(0L, WebNoticeArbiter.msUntilBlink(Long.MAX_VALUE))
    }

    @Test
    fun `剩余延时会随使用归零重来`() {
        // 用了一次 → 闲置时间清零 → 又要等满十分钟
        assertEquals(0L, WebNoticeArbiter.msUntilBlink(WebNoticeArbiter.IDLE_MS))
        assertEquals(WebNoticeArbiter.IDLE_MS, WebNoticeArbiter.msUntilBlink(0))
    }

    // ---------- 节奏 ----------

    @Test
    fun `开头是亮着的`() {
        assertTrue(WebNoticeArbiter.phaseOn(0L))
    }

    @Test
    fun `亮段末尾仍亮暗段开头转暗`() {
        assertTrue(WebNoticeArbiter.phaseOn(WebNoticeArbiter.ON_MS - 1))
        assertFalse(WebNoticeArbiter.phaseOn(WebNoticeArbiter.ON_MS))
    }

    @Test
    fun `一个周期结束时回到亮段`() {
        assertTrue(WebNoticeArbiter.phaseOn(WebNoticeArbiter.CYCLE_MS))
    }

    @Test
    fun `第二个周期的同一时刻与第一个周期一致`() {
        for (t in 0..WebNoticeArbiter.CYCLE_MS step 37L) {
            assertEquals(
                "t=$t",
                WebNoticeArbiter.phaseOn(t),
                WebNoticeArbiter.phaseOn(t + WebNoticeArbiter.CYCLE_MS)
            )
        }
    }

    @Test
    fun `亮暗时长符合约定的节奏`() {
        assertEquals(900L, WebNoticeArbiter.ON_MS)
        assertEquals(250L, WebNoticeArbiter.OFF_MS)
        assertEquals(1150L, WebNoticeArbiter.CYCLE_MS)
    }

    @Test
    fun `零时长参数不会除零`() {
        assertFalse(WebNoticeArbiter.phaseOn(0L, onMs = 0L, offMs = 0L))
        assertFalse(WebNoticeArbiter.phaseOn(0L, onMs = 0L, offMs = 500L))
    }

    // ---------- 组合 ----------

    @Test
    fun `组合判断在暗段不显示闪烁提示`() {
        assertFalse(WebNoticeArbiter.noticeVisible(inputs(), WebNoticeArbiter.ON_MS + 10))
    }

    @Test
    fun `组合判断在亮段显示闪烁提示`() {
        assertTrue(WebNoticeArbiter.noticeVisible(inputs(), 100L))
    }

    @Test
    fun `组合判断里避让优先于节奏`() {
        assertFalse(WebNoticeArbiter.noticeVisible(inputs(ctrl = true), 100L))
        assertFalse(WebNoticeArbiter.noticeVisible(inputs(temp = true), 100L))
    }

    @Test
    fun `组合判断里闲置不足优先于节奏`() {
        assertFalse(WebNoticeArbiter.noticeVisible(inputs(idleMs = 1000), 100L))
    }

    @Test
    fun `轮询间隔足够细`() {
        // 暗段 250ms，若轮询间隔 >= 暗段时长就可能整段错过（提示看起来不闪）
        assertTrue(WebNoticeArbiter.TICK_MS < WebNoticeArbiter.OFF_MS)
        assertTrue(WebNoticeArbiter.TICK_MS >= 50L)
    }
}
