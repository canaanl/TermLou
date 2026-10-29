@file:Suppress("MagicNumber")

package com.workspace.proot

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 5.10.1 真机回归：用真实 Android 字体把 Network 多 App 警告按手机宽度 measure，
 * 确认它确实折行、展示期间状态栏高度锁一行、scrollY 真的在动。
 */
@RunWith(AndroidJUnit4::class)
class StatusMarqueeInstrumentedTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(40)

    @Test
    fun networkWarningScrollsWithoutGrowingStatusBar() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val done = AtomicBoolean(false)
        var handler: Handler? = null
        lateinit var view: TextView
        var oneLine = 0
        var oldHeight = 0

        ActivityScenario.launch(DialogMakerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = FrameLayout(activity)
                activity.setContentView(root)
                val width = (activity.resources.displayMetrics.widthPixels * 0.6f).toInt()
                    .coerceAtLeast(160)
                view = TextView(activity).apply {
                    textSize = 13f
                    includeFontPadding = false
                    setPadding(16, 4, 16, 4)
                    text = activity.getString(R.string.net_multi_app_warn)
                }
                root.addView(
                    view,
                    FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT),
                )
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
                )
                root.layout(0, 0, width, 2000)
                assertTrue("warning must wrap for this regression: lines=${view.lineCount}", view.lineCount > 1)
                oneLine = view.lineHeight + view.paddingTop + view.paddingBottom
                oldHeight = view.layoutParams.height
                handler = Handler(Looper.getMainLooper())
                StatusMarquee.show(
                    view,
                    view.text.toString(),
                    2000L,
                    handler,
                ) { done.set(true) }
                assertEquals(oneLine, view.layoutParams.height)
            }

            instr.waitForIdleSync()
            Thread.sleep(700L)
            instr.waitForIdleSync()
            scenario.onActivity {
                assertTrue("status bar must scroll, scrollY=${view.scrollY}", view.scrollY > 0)
                assertEquals("status bar must stay one line high", oneLine, view.layoutParams.height)
            }

            Thread.sleep(1600L)
            instr.waitForIdleSync()
            scenario.onActivity {
                assertTrue("restore callback must run", done.get())
                assertEquals("height must be restored after the warning", oldHeight, view.layoutParams.height)
                StatusMarquee.cancel(view, handler)
            }
        }
    }
}
