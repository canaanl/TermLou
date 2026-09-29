package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 产物路径的锁（5.9.5）。
 *
 * 此前所有说明都写 `~/web/...`，而 proot 是用 `-b <workspace>:/workspace` 挂工作区的，
 * `HOME=/root` 指向 rootfs 里的**另一个**目录 —— agent 在终端里 `cat ~/web/web.env`
 * 直接报 `No such file or directory`，`shot` 返回的路径同样打不开。
 * "产物 Linux 可见"这个需求一直是坏的，而且被一条测试断言锁住了。
 */
class WebPathsTest {

    @Test
    fun `工作区挂载点就是 proot 绑的那个`() {
        // 与 TerminalManager.buildProotArgs 里的 `-w /workspace` / `-b <ws>:/workspace` 一致
        assertEquals("/workspace", WebProtocol.WORKSPACE_MOUNT)
    }

    @Test
    fun `产物目录挂在工作区下面而不是 home 下面`() {
        assertEquals("/workspace/web", WebProtocol.WEB_DIR)
        assertTrue(
            "产物目录必须在 WORKSPACE_MOUNT 之下，否则又变回 ~/web 了",
            WebProtocol.WEB_DIR.startsWith(WebProtocol.WORKSPACE_MOUNT + "/")
        )
        assertFalse(
            "不许以 ~ 开头 —— Linux 的 ~ 是 rootfs 里的 /root",
            WebProtocol.WEB_DIR.startsWith("~")
        )
    }

    @Test
    fun `说明书里的产物路径与常量一致且不再出现 ~`() {
        val help = WebProtocol.help(39080, "deadbeef")
        assertTrue(
            "说明书必须给出真路径 ${WebProtocol.WEB_DIR}/web.env",
            help.contains("${WebProtocol.WEB_DIR}/web.env")
        )
        assertFalse("产物路径不许再写成 ~/web", help.contains("~/web"))
    }
}
