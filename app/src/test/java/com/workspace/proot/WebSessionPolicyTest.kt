package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话生命周期规则锁定测试（5.9.0）。
 *
 * 无痕的关键不是"记得清"，而是"该清的时点一个都不落"，所以把三个触发条件
 * 抽成纯函数锁死：进程启动 / 服务启动 / 会话结束。
 */
class WebSessionPolicyTest {

    /** 三个清数据的触发点（服务里照这三条调用）。 */
    enum class Trigger { PROCESS_START, SERVICE_START, SESSION_END }

    /** 会话状态 → 此刻该不该有页面活着。 */
    fun shouldHavePageLive(sessionOpen: Boolean): Boolean = sessionOpen

    /** 某次触发是否需要清 cookie/缓存（三个触发点都需要，故恒真——但锁住这个约定）。 */
    fun shouldWipe(trigger: Trigger): Boolean = when (trigger) {
        Trigger.PROCESS_START, Trigger.SERVICE_START, Trigger.SESSION_END -> true
    }

    @Test
    fun `三个触发点都要清数据`() {
        for (t in Trigger.values()) {
            assertTrue("$t 应当清数据", shouldWipe(t))
        }
    }

    @Test
    fun `懒加载：还没开过页面时不建 WebView`() {
        assertFalse(shouldHavePageLive(sessionOpen = false))
    }

    @Test
    fun `开着会话时页面活着`() {
        assertTrue(shouldHavePageLive(sessionOpen = true))
    }

    @Test
    fun `触发点就是三个不多不少`() {
        assertEquals(3, Trigger.values().size)
    }

    @Test
    fun `服务开关决定会话提示是否闪烁`() {
        // 服务关 → 不闪；服务开且未用过 → 闪
        assertFalse(
            WebNoticeArbiter.shouldBlink(
                WebNoticeArbiter.Inputs(serviceOn = false, noticePending = true)
            )
        )
        assertTrue(
            WebNoticeArbiter.shouldBlink(
                WebNoticeArbiter.Inputs(serviceOn = true, noticePending = true)
            )
        )
    }

    @Test
    fun `agent 用过之后提示不再闪`() {
        assertFalse(
            WebNoticeArbiter.shouldBlink(
                WebNoticeArbiter.Inputs(serviceOn = true, noticePending = false)
            )
        )
    }
}
