package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 5.9.59：进厂商高刷名单这件事的锁。
 *
 * ## 为什么扫源码
 *
 * `android:isGame="true"` 和 `game_mode_config.xml` 的四个开关都是**配置**，
 * 没有编译期接口能在"配错了"时编译失败 —— 全是运行期行为，单测碰不到。
 * 所以这里扫源码把形状钉死。
 *
 * ## 这条锁真正要防的事
 *
 * 不是"忘了加 isGame"，而是**加了却把开关配错**：那比不加更糟。
 * 一旦声明 `supportsPerformanceGameMode`，官方文档写明系统会
 * **清空厂商施加的所有 Game Mode 干预** —— 高刷立刻又没了，
 * 而用户会以为"代码写了啊"。
 */
class GameModeLockTest {

    private fun repoFile(path: String): File {
        var d: File? = File("").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            val f = File(d, path)
            if (f.isFile) return f
            d = d.parentFile
            hops++
        }
        throw AssertionError("找不到 $path，这条检查等于没跑")
    }

    private fun manifest(): String =
        repoFile("app/src/main/AndroidManifest.xml").readText()

    private fun gameModeConfig(): String =
        repoFile("app/src/main/res/xml/game_mode_config.xml").readText()

    // ---------- 进名单的前提 ----------

    @Test
    fun `必须声明是游戏`() {
        val m = manifest()
        assertTrue(
            "必须声明 android:isGame=\"true\"，否则厂商高刷白名单吃不到我们：\n$m",
            m.contains("android:isGame=\"true\"")
        )
    }

    @Test
    fun `游戏模式配置必须挂进manifest`() {
        val m = manifest()
        assertTrue(
            "必须用 meta-data 挂上 android.game_mode_config：\n$m",
            m.contains("android:name=\"android.game_mode_config\"") &&
                m.contains("android:resource=\"@xml/game_mode_config\"")
        )
    }

    @Test
    fun `游戏模式配置文件必须存在`() {
        assertTrue(
            "res/xml/game_mode_config.xml 不存在 —— meta-data 会指向空资源",
            repoFile("app/src/main/res/xml/game_mode_config.xml").isFile
        )
        assertTrue(
            "根节点必须是 <game-mode-config>",
            gameModeConfig().contains("<game-mode-config")
        )
    }

    // ---------- 四个开关，一个都不能开错 ----------

    @Test
    fun `不许接管性能或省电游戏模式`() {
        // 官方文档原文：声明支持会让系统「重置厂商此前施加的所有 Game Mode 干预」。
        // 也就是我们费劲进的名单会自己被清掉。
        val x = gameModeConfig()
        for (flag in listOf("supportsPerformanceGameMode", "supportsBatteryGameMode")) {
            assertTrue(
                "$flag 必须显式写成 false —— 写 true 等于让厂商那套（含高刷）失效：\n$x",
                x.contains("android:$flag=\"false\"")
            )
            assertTrue(
                "$flag 不能出现 true：\n$x",
                !x.contains("android:$flag=\"true\"")
            )
        }
    }

    @Test
    fun `不许让厂商降分辨率`() {
        // 厂商的游戏模式会降分辨率渲染再放大以省电。对游戏是画质取舍，
        // 对终端是文字糊掉 —— 这个 app 的立身之本就没了。
        val x = gameModeConfig()
        assertTrue(
            "allowGameDownscaling 必须 false，否则终端文字会糊：\n$x",
            x.contains("android:allowGameDownscaling=\"false\"")
        )
        assertTrue("allowGameDownscaling 不能出现 true：\n$x", !x.contains("android:allowGameDownscaling=\"true\""))
    }

    @Test
    fun `不许让厂商把FPS限回去`() {
        // FPS throttling intervention（Android 13+）会把游戏限到 60Hz。
        // 我们正在争取高刷，让厂商限回去等于白做。
        val x = gameModeConfig()
        assertTrue(
            "allowGameFpsOverride 必须 false：\n$x",
            x.contains("android:allowGameFpsOverride=\"false\"")
        )
        assertTrue("allowGameFpsOverride 不能出现 true：\n$x", !x.contains("android:allowGameFpsOverride=\"true\""))
    }

    // ---------- 官方给游戏用的那条 API（反射） ----------

    @Test
    fun `必须调官方给游戏用的那条setFrameRate`() {
        val m = repoFile("app/src/main/java/com/workspace/proot/MainActivity.kt")
            .readText()
            .lines()
            .map { it.trimStart().let { t -> if (t.startsWith("*") || t.startsWith("/*")) "" else t } }
            .joinToString("\n")
        assertTrue(
            "必须反射调用 Window.setFrameRate（API 35+，官方给游戏用的正路）：\n$m",
            m.contains("\"setFrameRate\"")
        )
        assertTrue(
            "必须按 SDK_INT >= 35 把门 —— 低版本上方法根本不存在：\n$m",
            m.contains("Build.VERSION.SDK_INT < 35")
        )
        assertTrue(
            "反射必须 runCatching 兜死，不许让介绍页或冷启动崩：\n$m",
            m.contains("runCatching") && m.contains("m.invoke(window")
        )
    }

    // ---------- 诊断读数 ----------

    @Test
    fun `游戏模式常量必须与AOSP一致`() {
        // 反射/系统返回的是裸 int，值错了读数就会指错方向
        assertEquals(0, DisplayRefresh.GAME_MODE_UNSUPPORTED)
        assertEquals(1, DisplayRefresh.GAME_MODE_STANDARD)
        assertEquals(2, DisplayRefresh.GAME_MODE_PERFORMANCE)
        assertEquals(3, DisplayRefresh.GAME_MODE_BATTERY)
        assertEquals(4, DisplayRefresh.GAME_MODE_CUSTOM)
    }

    @Test
    fun `不支持游戏模式时读数必须说人话`() {
        // "不支持"是用户去系统设置手动加名单的唯一信号，不能只印个 0
        val res = repoFile("app/src/main/res/values/strings.xml").readText()
        assertTrue(
            "必须有「不支持」文案 —— 它是让用户去手动加名单的提示：\n$res",
            res.contains("sysinfo_gamemode_unsupported") &&
                res.contains("不支持")
        )
        for (k in listOf("standard", "performance", "battery", "custom")) {
            assertTrue("缺 gamemode_$k 文案", res.contains("sysinfo_gamemode_$k"))
        }
    }
}
