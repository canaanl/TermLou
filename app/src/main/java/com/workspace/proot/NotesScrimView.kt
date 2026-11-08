@file:Suppress("MagicNumber")

package com.workspace.proot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * 笔记 Tab 内容区的模态遮罩（5.11.1）。
 *
 * - 只盖笔记内容区，不盖状态栏 / 顶部 Tab / 底部待办日历；
 * - 整体一层很轻的黑，再加一层以 FAB 为圆心的径向黑，FAB 附近最重，
 *   视觉来源与文件页 [BraceMenu] 的 scrim 同一套色板（[UiTokens.scrimStart]）；
 * - 点空白**不关闭**，只吞掉点击；
 * - **绝不调用 requestDisallowInterceptTouchEvent**：横滑切 Tab 是最高优先级，
 *   手势必须继续交给外层 ViewPager2。这是本类唯一的硬约束，测试专门盯着它。
 */
class NotesScrimView(context: Context) : View(context) {

    private val basePaint = Paint()
    private val radialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var blocking = false

    init {
        setWillNotDraw(false)
        visibility = GONE
        isClickable = false
        isFocusable = false
    }

    /** 以内容区坐标设置径向渐变圆心（由 FAB 组在布局后提供）。 */
    fun setAnchor(x: Float, y: Float) {
        if (x == anchorX && y == anchorY) return
        anchorX = x
        anchorY = y
        invalidate()
    }

    /** 0..1，展开/收起时与 FAB 动画同步。 */
    fun setProgress(value: Float) {
        val p = value.coerceIn(0f, 1f)
        if (p == progress) return
        progress = p
        invalidate()
    }

    fun setBlocking(value: Boolean) {
        blocking = value
        isClickable = value
        isFocusable = value
    }

    val isBlocking: Boolean get() = blocking

    val shownProgress: Float get() = progress

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0.001f) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 整体轻黑：让列表“微微泛灰黑”。
        basePaint.color = 0xFF000000.toInt()
        basePaint.alpha = (0x26 * progress).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, h, basePaint)

        // FAB 附近重点发黑：与 BraceMenu 相同的径向色，只是圆心在 FAB。
        val radius = max(w, h).coerceAtLeast(1f) * 0.9f
        radialPaint.shader = RadialGradient(
            anchorX,
            anchorY,
            radius,
            intArrayOf(UiTokens.scrimStart, 0x00000000),
            null,
            Shader.TileMode.CLAMP,
        )
        radialPaint.alpha = (0x99 * progress).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, h, radialPaint)
        radialPaint.shader = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 只拦点击；横向手势由外层 ViewPager2 正常拦截。
        return blocking
    }
}
