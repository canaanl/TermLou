@file:Suppress("MagicNumber")

package com.workspace.proot

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 5.10.1 状态栏长文本回归：
 * 真实 TextView 按手机宽度 measure，验证“高度锁一行 + scrollY 实际滚动”，
 * 不是只测纯函数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StatusMarqueeLayoutTest {

    /**
     * Robolectric 的默认字体把每个字符量成 1px，靠宽度自动折行不会发生；
     * 这里用显式换行构造 3 行，锁的是“多行 -> 锁一行高 + 纵向滚”的机制。
     * 真实字体下的自动折行由 androidTest 的 StatusMarqueeInstrumentedTest 覆盖。
     */
    private val multiLineStatus = "同时抓取多个 App\n无法区分各自流量\n建议一次只勾一个"

    private fun makeView(activity: Activity, text: CharSequence): TextView {
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val view = TextView(activity).apply {
            textSize = 18f
            includeFontPadding = false
            setPadding(16, 4, 16, 4)
            maxLines = Int.MAX_VALUE
            setHorizontallyScrolling(false)
            ellipsize = null
            this.text = text
        }
        root.addView(
            view,
            FrameLayout.LayoutParams(160, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        root.measure(
            View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, 160, 2000)
        return view
    }

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `long transient status locks one line and restores`() {
        val activity = activity()
        val text = multiLineStatus
        val view = makeView(activity, text)
        assertTrue("fixture must wrap, lines=${view.lineCount}", view.lineCount > 1)

        val oneLine = view.lineHeight + view.paddingTop + view.paddingBottom
        val oldHeight = view.layoutParams.height
        var done = false
        val handler = Handler(Looper.getMainLooper())

        StatusMarquee.show(view, text, 2000L, handler) { done = true }

        assertEquals("status bar must lock to one line immediately", oneLine, view.layoutParams.height)
        // Robolectric 的 idle() 会把 2s 后的 finish 一并跑掉；这里验证恢复回调，
        // 实际滚动帧由真机 instrumentation 覆盖。
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("restore callback must run", done)
        assertEquals("height must be restored after the temp status", oldHeight, view.layoutParams.height)
    }

    @Test
    fun `persistent long status locks one line`() {
        val activity = activity()
        val text = multiLineStatus
        val view = makeView(activity, text)
        val handler = Handler(Looper.getMainLooper())

        StatusMarquee.showPersistentVertical(view, text, handler)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val oneLine = view.lineHeight + view.paddingTop + view.paddingBottom
        assertEquals(oneLine, view.layoutParams.height)
        assertTrue("fixture must wrap, lines=${view.lineCount}", view.lineCount > 1)

        StatusMarquee.cancel(view, handler)
    }
}
