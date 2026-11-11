package com.workspace.proot

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 主 PRoot 存活判定与 60 秒监控回归（5.12.3）。
 *
 * 监控的核心不是“服务绑定到哪个会话”，而是“主 PRoot 进程还在不在”。
 * 这里用 fake /proc 锁定四种情况：存活、进程消失、僵尸、PID 复用（starttime 不一致）。
 */
@Suppress("MagicNumber")
class MainProotTrackerTest {

    private lateinit var proc: File

    @Before
    fun setUp() {
        proc = Files.createTempDirectory("term-lou-main-proot").toFile()
    }

    @After
    fun tearDown() {
        proc.deleteRecursively()
        MainProotTracker.reset()
    }

    private fun procEntry(pid: Int, state: String = "S", startTime: Long = 1000L, comm: String = "proot") {
        val dir = File(proc, pid.toString()).apply { mkdirs() }
        // stat 字段：state 后依次 ppid/pgrp/session/tty_nr/tpgid/flags/minflt/cminflt/
        // majflt/cmajflt/utime/stime/cutime/cstime/priority/nice/num_threads/itrealvalue/starttime
        val rest = (1..18).joinToString(" ") { "0" }
        File(dir, "stat").writeText("$pid ($comm) $state $rest $startTime 0 0\n")
    }

    @Test
    fun `alive process with matching start time counts as alive`() {
        procEntry(4321, "S", 555L)
        MainProotTracker.record(4321, 555L)
        assertTrue(MainProotTracker.isAlive(proc))
        assertEquals(4321, MainProotTracker.currentPid())
    }

    @Test
    fun `missing process counts as not alive`() {
        MainProotTracker.record(4321, 555L)
        assertFalse(MainProotTracker.isAlive(proc))
    }

    @Test
    fun `zombie counts as not alive`() {
        procEntry(4321, "Z", 555L)
        MainProotTracker.record(4321, 555L)
        assertFalse(MainProotTracker.isAlive(proc))
    }

    @Test
    fun `reused pid with different start time counts as not alive`() {
        procEntry(4321, "S", 999L)
        MainProotTracker.record(4321, 555L)
        assertFalse("starttime 不一致说明是复用 PID 的新进程：\n$proc", MainProotTracker.isAlive(proc))
    }

    @Test
    fun `never started means no main PRoot`() {
        procEntry(4321, "S", 555L)
        MainProotTracker.reset()
        assertFalse(MainProotTracker.isAlive(proc))
        assertEquals(0, MainProotTracker.currentPid())
    }

    @Test
    fun `finish only clears the matching pid`() {
        MainProotTracker.record(4321, 555L)
        // 旧会话的迟到回调不能误清新主 PRoot。
        MainProotTracker.onFinished(9999)
        assertEquals(4321, MainProotTracker.currentPid())
        MainProotTracker.onFinished(4321)
        assertEquals(0, MainProotTracker.currentPid())
    }

    @Test
    fun `finish without pid keeps a live main PRoot`() {
        procEntry(4321, "S", 555L)
        MainProotTracker.record(4321, 555L)
        // PTY 退出回调里 pid 已被清成 -1；主 PRoot 仍活着时不能误清。
        MainProotTracker.onFinished(-1, proc)
        assertEquals(4321, MainProotTracker.currentPid())
    }

    @Test
    fun `finish without pid clears a dead main PRoot`() {
        MainProotTracker.record(4321, 555L)
        MainProotTracker.onFinished(-1, proc)
        assertEquals(0, MainProotTracker.currentPid())
    }

    @Test
    fun `comm with spaces and parentheses still parses`() {
        procEntry(777, "S", 42L, comm = "proot (weird) name")
        val info = ProcLiveness.info(777, proc)
        assertEquals("S", info?.state)
        assertEquals(42L, info?.startTime)
    }

    @Test
    fun `proc read failure fails closed`() {
        // /proc 不可读（这里用不存在目录模拟）必须判为不存活，绝不赌它还活着。
        val missing = File(proc, "no-such-proc")
        assertFalse(ProcLiveness.isAlive(4321, 555L, missing))
    }

    @Test
    fun `zero or negative pid is never alive`() {
        assertFalse(ProcLiveness.isAlive(0, null, proc))
        assertFalse(ProcLiveness.isAlive(-1, null, proc))
    }
}
