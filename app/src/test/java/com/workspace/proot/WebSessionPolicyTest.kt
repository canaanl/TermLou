package com.workspace.proot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话生命周期规则锁定测试（5.9.0 建立，5.9.38 重写）。
 *
 * ## 5.9.38：把三条**假锁**换成了真的
 *
 * 原来这个文件里有一个自造的 `enum Trigger` 和一个 `shouldWipe()`（恒返回 true），
 * 然后断言"它返回 true"、"触发点刚好 3 个" —— **测的是它自己，不是产品**。
 * 服务里到底有没有在那三个时点清数据，它一个字都没管。
 *
 * 那种锁比没有更糟：它让"无痕"这件事看起来有人盯着，实际上没有 ——
 * 而 5.9.38 真的查出"清缓存"那三行**从来没执行过**（写在销毁之后，读到的是 null）。
 *
 * 现在改成**扫真代码里的调用点**：清数据的每个时点都必须在源码里找得到位置，
 * 而且必须落在"能真的清到"的地方（清 WebView 相关的东西时，WebView 必须还活着）。
 */
class WebSessionPolicyTest {

    private fun code(name: String): String {
        var d: File? = File("").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            val f = File(d, "app/src/main/java/com/workspace/proot/$name")
            if (f.isFile) {
                return f.readText().lines()
                    .map { it.trimStart().let { t -> if (t.startsWith("*") || t.startsWith("/*")) "" else it } }
                    .joinToString("\n")
            }
            d = d.parentFile
            hops++
        }
        throw AssertionError("找不到 $name，这条检查等于没跑")
    }

    // ---------- 三个时点：进程启动 / 服务启动 / 会话销毁 ----------

    @Test
    fun `进程启动要清 cookie 与 localStorage`() {
        assertTrue(
            "进程启动必须清（见 TermLouApp.onCreate）：\n" + code("TermLouApp.kt"),
            code("TermLouApp.kt").contains("wipeOnProcessStart(this)")
        )
        val svc = code("WebAutomationService.kt")
        val wipe = svc.substringAfter("fun wipeOnProcessStart(").substringBefore("private const val PREFS")
        assertTrue("process-start 清理没抓到", wipe.length > 100)
        assertTrue("要清 cookie：\n$wipe", wipe.contains("removeAllCookies(null)"))
        assertTrue("要清 localStorage/IndexedDB：\n$wipe", wipe.contains("deleteAllData()"))
    }

    @Test
    fun `服务启动要清 cookie 与 localStorage`() {
        val svc = code("WebAutomationService.kt")
        val start = svc.substringAfter("private fun startServer()").substringBefore("private fun acceptLoop(")
        assertTrue("没抓到 startServer", start.length > 200)
        assertTrue(
            "服务启动即无痕，必须清：\n$start",
            start.contains("clearCookiesAndStorage()")
        )
    }

    @Test
    fun `会话销毁要清 而且必须在 WebView 还活着的时候清`() {
        val svc = code("WebAutomationService.kt")
        val destroy = svc.substringAfter("private fun destroySession()")
            .substringBefore("private fun wipeWebViewData(")
        assertTrue("没抓到 destroySession", destroy.length > 200)
        // 不依赖 WebView 的两样
        assertTrue("要清 cookie/localStorage：\n$destroy", destroy.contains("clearCookiesAndStorage()"))
        // 依赖 WebView 的一样 —— 必须**先清后销毁**（5.9.37 就是反了，那三行从没跑过）
        val clearAt = destroy.indexOf("wipeWebViewData(wv)")
        val destroyAt = destroy.indexOf("wv.destroy()")
        assertTrue("销毁前必须先清 WebView 的缓存/历史/表单：\n$destroy", clearAt in 0 until destroyAt)
    }

    @Test
    fun `关闭服务与 close 指令都走同一个销毁路径`() {
        val svc = code("WebAutomationService.kt")
        assertTrue(
            "关闭服务必须销毁会话（清数据在里面）：\n$svc",
            svc.substringAfter("private fun teardown()").substringBefore("// ---------- HTTP 分发")
                .contains("destroySession()")
        )
        assertTrue(
            "close 指令必须销毁会话：\n$svc",
            svc.substringAfter("private fun opClose()").substringBefore("// ---------- 通知")
                .contains("destroySession()")
        )
    }

    // ---------- 空窗提示的仲裁（这几条本来就是真的） ----------

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
