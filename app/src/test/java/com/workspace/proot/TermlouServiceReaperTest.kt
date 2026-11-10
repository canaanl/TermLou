package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PID 复用安全回归：root 的 starttime 一旦记录，绝不能被新的 /proc 查询覆盖。
 * 这是 service stop 不误杀“复用同一 PID 的新进程”的最后一道锁。
 */
@Suppress("MagicNumber")
class TermlouServiceReaperTest {

    @Test
    fun `trackRoot never overwrites an existing start time`() {
        val tracked = linkedMapOf<Int, Long?>(100 to 111L)
        TermlouServiceReaper.trackRoot(tracked, 100, 222L)
        assertEquals(111L, tracked[100])
        assertEquals(1, tracked.size)
    }

    @Test
    fun `trackRoot adds a missing root start time`() {
        val tracked = linkedMapOf<Int, Long?>()
        TermlouServiceReaper.trackRoot(tracked, 100, 111L)
        assertEquals(111L, tracked[100])
        assertEquals(1, tracked.size)
    }
}
