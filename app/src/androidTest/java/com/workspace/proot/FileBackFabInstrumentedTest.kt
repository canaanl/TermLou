@file:Suppress("MagicNumber")

package com.workspace.proot

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * 5.10.2 真机回归：返回 FAB 的禁用/启用色和点击语义在真实 Android 字体与
 * 主题实例下成立（Robolectric 覆盖几何，真机覆盖实际渲染路径）。
 */
@RunWith(AndroidJUnit4::class)
class FileBackFabInstrumentedTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    @Test
    fun disabledFABReportsAndEnabledFABNavigates() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val up = AtomicInteger(0)
        val blocked = AtomicInteger(0)
        lateinit var fab: FileBackFab
        lateinit var theme: ThemeColors

        ActivityScenario.launch(DialogMakerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                theme = ThemeColors.default(true)
                fab = FileBackFab(activity, theme, { up.incrementAndGet() }, { blocked.incrementAndGet() })
                activity.setContentView(fab)
                fab.canGoUp = false
                assertTrue(
                    "disabled arrow must stay visible",
                    ColorMath.contrast(fab.currentTextColor, theme.surface) >= 1.95,
                )
                fab.performClick()
                fab.canGoUp = true
                assertEquals(theme.primaryVisible, fab.currentTextColor)
                fab.performClick()
            }
            instr.waitForIdleSync()
            scenario.onActivity {
                assertEquals("enabled tap must navigate", 1, up.get())
                assertEquals("root tap must report", 1, blocked.get())
            }
        }
    }
}
