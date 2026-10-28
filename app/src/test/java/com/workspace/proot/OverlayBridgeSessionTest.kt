@file:Suppress("MagicNumber")

package com.workspace.proot

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.io.File
import java.time.Duration

/**
 * 会话级空闲超时 + 会话关闭清队列的真机同款 View 环境测试。
 * 用显式 1s/2s 超时避免测试等 30s。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OverlayBridgeSessionTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val base = File(ctx.cacheDir, "termlou-session-test").apply {
        deleteRecursively()
        mkdirs()
    }

    @After
    fun cleanup() {
        OverlayBridge.activeOverlayForTest()?.dismiss(immediate = true)
        OverlayBridge.release()
        base.deleteRecursively()
    }

    @Test
    fun sessionIdleTimeoutAndActivityReset() {
        ShadowSettings.setCanDrawOverlays(true)
        OverlayBridge.acquire(ctx, base)
        val reqDir = File(base, "req").apply { mkdirs() }
        val resDir = File(base, "res").apply { mkdirs() }

        // 1. 不传 timeout：App 默认 30s。
        writeRequest(reqDir, "default.json", timeout = null)
        awaitOverlay()
        assertEquals(30_000L, OverlayBridge.sessionTimeoutMsForTest())
        OverlayBridge.activeOverlayForTest()?.onSessionClose?.invoke()
        awaitResult(File(resDir, "default.json"))
        awaitClosed()

        // 2. 显式 45s，后续不传保留 45s。
        writeRequest(reqDir, "explicit.json", timeout = 45)
        awaitOverlay()
        assertEquals(45_000L, OverlayBridge.sessionTimeoutMsForTest())
        writeRequest(reqDir, "explicit2.json", timeout = null)
        awaitCondition("second request did not update") {
            OverlayBridge.activeOverlayForTest() != null &&
                OverlayBridge.sessionTimeoutMsForTest() == 45_000L
        }
        OverlayBridge.activeOverlayForTest()?.onSessionClose?.invoke()
        awaitResult(File(resDir, "explicit2.json"))
        awaitClosed()

        // 3. 显式 1s：无操作自动 timeout。
        writeRequest(reqDir, "timeout.json", timeout = 1)
        awaitOverlay()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200))
        val timeoutResult = awaitResult(File(resDir, "timeout.json"))
        assertTrue("timeout result: $timeoutResult", timeoutResult.contains("\"id\":\"timeout\""))
        awaitClosed()

        // 4. 点击重置：2s 超时，1s 时点一下，再 1.5s 仍应活着。
        writeRequest(reqDir, "reset.json", timeout = 2)
        awaitOverlay()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        OverlayBridge.activeOverlayForTest()?.onUserActivity?.invoke()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertNotNull("activity did not reset the idle timer", OverlayBridge.activeOverlayForTest())
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        val resetResult = awaitResult(File(resDir, "reset.json"))
        assertTrue("timeout after reset: $resetResult", resetResult.contains("\"id\":\"timeout\""))
        awaitClosed()
    }

    private fun awaitClosed() {
        awaitCondition("overlay did not close") {
            OverlayBridge.activeOverlayForTest() == null
        }
    }

    private fun writeRequest(dir: File, name: String, timeout: Int?) {
        val timeoutJson = if (timeout == null) "" else ",\"timeout\":$timeout"
        val json = """{"id":"$name"$timeoutJson,"ui":{"title":"T","rows":[
            {"kind":"buttons","buttons":[{"text":"OK","id":"ok"}]}]}}"""
        File(dir, name).writeText(json)
    }

    private fun awaitOverlay() {
        awaitCondition("overlay did not appear") {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            OverlayBridge.activeOverlayForTest() != null
        }
    }

    private fun awaitResult(file: File): String {
        awaitCondition("result did not appear: ${file.name}") { file.isFile }
        return file.readText()
    }

    private fun awaitCondition(message: String, block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            if (block()) return
            Thread.sleep(10)
        }
        fail(message)
    }
}
