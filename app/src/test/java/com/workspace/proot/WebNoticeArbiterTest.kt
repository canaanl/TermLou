package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 闪烁提示的避让规则锁定测试（5.9.0）。
 *
 * 这是用户明确要求必须测的部分：闪烁与 status 栏其它信息的优先级
 * 手动点很难稳定复现（要在 2 秒临时提示的窗口里对上闪烁的暗段），
 * 所以规则抽成纯函数后逐条锁死。
 */
class WebNoticeArbiterTest {

    private fun inputs(
        serviceOn: Boolean = true,
        noticePending: Boolean = true,
        temp: Boolean = false,
        ctrl: Boolean = false
    ) = WebNoticeArbiter.Inputs(
        serviceOn = serviceOn,
        noticePending = noticePending,
        tempStatusActive = temp,
        ctrlInfoVisible = ctrl
    )

    // ---------- 何时该闪 ----------

    @Test
    fun `开着且没用过就闪`() {
        assertTrue(WebNoticeArbiter.shouldBlink(inputs()))
    }

    @Test
    fun `服务关着不闪`() {
        assertFalse(WebNoticeArbiter.shouldBlink(inputs(serviceOn = false)))
    }

    @Test
    fun `agent 已经用过就不闪`() {
        assertFalse(WebNoticeArbiter.shouldBlink(inputs(noticePending = false)))
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
        assertTrue(WebNoticeArbiter.phaseOn(0L, onMs = 0L, offMs = 500L).not())
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
    fun `轮询间隔足够细`() {
        // 暗段 250ms，若轮询间隔 >= 暗段时长就可能整段错过（提示看起来不闪）
        assertTrue(WebNoticeArbiter.TICK_MS < WebNoticeArbiter.OFF_MS)
        assertTrue(WebNoticeArbiter.TICK_MS >= 50L)
    }
}
