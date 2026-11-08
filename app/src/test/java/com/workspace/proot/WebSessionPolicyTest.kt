package com.workspace.proot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 浏览器会话策略锁定测试（5.9.0 建立；5.11.0 按"Cookie 持久 + 缓存仍清"重写）。
 *
 * 5.11.0 用户要求：
 * - UA 改成通用移动 Chrome（去掉 `; wv` / `Version/4.0`）；
 * - Cookie 持久化，正常开关服务/销毁会话/进程启动都不清；
 * - 只有设置里「清除缓存」这一条路径清 Cookie；
 * - 缓存策略不变（`LOAD_NO_CACHE` + 实例创建/销毁时 `clearCache`）。
 *
 * 这里扫真代码的调用点，避免"锁住一个自造的布尔值"那种假锁（5.9.38 的教训）。
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

    @Test
    fun `进程启动不再清 cookie`() {
        val app = code("TermLouApp.kt")
        assertFalse(
            "进程启动不许再清浏览器数据（cookie 要跨进程保留）：\n$app",
            app.contains("wipeOnProcessStart")
        )
        assertFalse(
            "进程启动不许直接调 removeAllCookies：\n$app",
            app.contains("removeAllCookies")
        )
    }

    @Test
    fun `服务启动只清 web storage 并开启 cookie`() {
        val svc = code("WebAutomationService.kt")
        val start = svc.substringAfter("private fun startServer()").substringBefore("private fun acceptLoop(")
        assertTrue("没抓到 startServer", start.length > 200)
        assertTrue("服务启动要显式接受 cookie：\n$start", start.contains("setAcceptCookie(true)"))
        assertTrue("服务启动仍清 localStorage/IndexedDB：\n$start", start.contains("clearWebStorage()"))
        assertFalse(
            "服务启动绝不许清 cookie：\n$start",
            start.contains("removeAllCookies")
        )
    }

    @Test
    fun `UA 必须是通用移动 Chrome`() {
        val svc = code("WebAutomationService.kt")
        assertTrue(
            "ensureWebView 必须从默认 UA 派生通用移动 UA：\n$svc",
            svc.contains("WebUserAgent.genericMobile(")
        )
        assertTrue(
            "派生结果必须写回 WebSettings：\n$svc",
            svc.contains("userAgentString = genericUa")
        )
        val ua = code("WebUserAgent.kt")
        assertTrue("必须去掉 ; wv：\n$ua", ua.contains("wv"))
        assertTrue("必须去掉 Version/4.0：\n$ua", ua.contains("Version/4"))
    }

    @Test
    fun `会话销毁清缓存但不许清 cookie`() {
        val svc = code("WebAutomationService.kt")
        val destroy = svc.substringAfter("private fun destroySession()")
            .substringBefore("private fun wipeWebViewData(")
        assertTrue("没抓到 destroySession", destroy.length > 200)
        // 依赖 WebView 的缓存/历史/表单 —— 必须**先清后销毁**
        val clearAt = destroy.indexOf("wipeWebViewData(wv)")
        val destroyAt = destroy.indexOf("wv.destroy()")
        assertTrue("销毁前必须先清 WebView 的缓存/历史/表单：\n$destroy", clearAt in 0 until destroyAt)
        assertTrue("localStorage/IndexedDB 仍按旧策略清：\n$destroy", destroy.contains("clearWebStorage()"))
        assertTrue("关闭前要 flush cookie：\n$destroy", destroy.contains("flushCookies()"))
        assertFalse("会话销毁绝不许清 cookie：\n$destroy", destroy.contains("removeAllCookies"))
    }

    @Test
    fun `关闭服务与 close 指令都走同一个销毁路径`() {
        val svc = code("WebAutomationService.kt")
        assertTrue(
            "关闭服务必须销毁会话：\n$svc",
            svc.substringAfter("private fun teardown()").substringBefore("// ---------- HTTP 分发")
                .contains("destroySession()")
        )
        assertTrue(
            "close 指令必须销毁会话：\n$svc",
            svc.substringAfter("private fun opClose()").substringBefore("// ---------- 通知")
                .contains("destroySession()")
        )
    }

    @Test
    fun `设置清理是唯一清 cookie 的路径`() {
        val ctl = code("WebAutomationController.kt")
        val clear = ctl.substringAfter("private fun confirmClear()").substringBefore("\n    }")
        assertTrue("设置清理必须调用 clearBrowserData：\n$clear", clear.contains("clearBrowserData"))

        val svc = code("WebAutomationService.kt")
        val cleanup = svc.substringAfter("fun clearBrowserData(context: Context)")
            .substringBefore("private const val PREFS")
        assertTrue("clearBrowserData 没抓到", cleanup.length > 100)
        assertTrue("设置清理要删 cookie：\n$cleanup", cleanup.contains("removeAllCookies(null)"))
        assertTrue("设置清理要删站点存储：\n$cleanup", cleanup.contains("deleteAllData()"))
        assertTrue("设置清理要清表单：\n$cleanup", cleanup.contains("clearFormData()"))
    }

    @Test
    fun `缓存策略保持不变`() {
        val svc = code("WebAutomationService.kt")
        assertTrue("缓存仍不持久：\n$svc", svc.contains("cacheMode = WebSettings.LOAD_NO_CACHE"))
        assertTrue("实例销毁/创建时仍清缓存：\n$svc", svc.contains("wv.clearCache(true)"))
    }

    // ---------- 空窗提示的仲裁 ----------

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
