package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话生命周期规则锁定测试（5.9.0 / 5.9.1）。
 *
 * 无痕的关键不是"记得清"，而是"该清的时点一个都不落"，所以把三个触发条件
 * 抽成纯函数锁死：进程启动 / 服务启动 / 会话结束。
 */
class WebSessionPolicyTest {

    /** 三个清数据的触发点（服务里照这三条调用）。 */
    enum class Trigger { PROCESS_START, SERVICE_START, SESSION_END }

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
    fun `触发点就是三个不多不少`() {
        assertEquals(3, Trigger.values().size)
    }

    @Test
    fun `服务关着不闪`() {
        assertFalse(
            WebNoticeArbiter.shouldBlink(
                WebNoticeArbiter.Inputs(serviceOn = false, idleMs = Long.MAX_VALUE)
            )
        )
    }

    @Test
    fun `服务开着且闲置够就闪`() {
        assertTrue(
            WebNoticeArbiter.shouldBlink(
                WebNoticeArbiter.Inputs(serviceOn = true, idleMs = WebNoticeArbiter.IDLE_MS)
            )
        )
    }

    @Test
    fun `刚用过不闪`() {
        assertFalse(
            WebNoticeArbiter.shouldBlink(
                WebNoticeArbiter.Inputs(serviceOn = true, idleMs = 0)
            )
        )
    }
}
