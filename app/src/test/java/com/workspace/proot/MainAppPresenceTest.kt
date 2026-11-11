package com.workspace.proot

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 主程序打开状态回归（5.12.4）。
 *
 * 后台服务的唯一存活条件就是“TermLou 主程序打开着”。
 * 这里锁定：默认未打开、打开后为真、关闭后为假、可反复开关。
 */
@Suppress("MagicNumber")
class MainAppPresenceTest {

    @Before
    fun setUp() {
        MainAppPresence.reset()
    }

    @After
    fun tearDown() {
        MainAppPresence.reset()
    }

    @Test
    fun `defaults to not open`() {
        assertFalse("从未打开过主程序时必须是未打开：", MainAppPresence.isOpen())
    }

    @Test
    fun `opening marks presence true`() {
        MainAppPresence.onMainOpened()
        assertTrue(MainAppPresence.isOpen())
    }

    @Test
    fun `closing marks presence false`() {
        MainAppPresence.onMainOpened()
        MainAppPresence.onMainClosed()
        assertFalse(MainAppPresence.isOpen())
    }

    @Test
    fun `presence can be reopened after close`() {
        MainAppPresence.onMainOpened()
        MainAppPresence.onMainClosed()
        assertFalse(MainAppPresence.isOpen())

        MainAppPresence.onMainOpened()
        assertTrue("重新打开主程序后服务条件恢复：", MainAppPresence.isOpen())
    }

    @Test
    fun `repeated open calls stay open`() {
        repeat(3) { MainAppPresence.onMainOpened() }
        assertTrue(MainAppPresence.isOpen())
    }

    @Test
    fun `reset clears presence`() {
        MainAppPresence.onMainOpened()
        MainAppPresence.reset()
        assertFalse(MainAppPresence.isOpen())
    }
}
