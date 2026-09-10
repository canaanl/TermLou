package com.workspace.proot

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * wheel 长按特效叠加层：从长按点开始发亮，波纹扩散至整条 BAND
 * （占位双层高的整个范围，无论当前单层还是双层）。
 * 不拦截触摸；辉光播完后回调 onEnd（用于打开 wheel 设置）。
 */
class WheelGlowOverlay(context: Context) : View(context) {

    /** 按住 1s 蓄力后从手指下方扩散至罩满整条 BAND 的时长。 */
    private val burstDurationMs = 1000L

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cx = 0f
    private var cy = 0f
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var onEnd: (() -> Unit)? = null

    init {
        isClickable = false
    }

    /** rawX/rawY 为屏幕坐标；返回 false 表示当前尺寸未就绪，本次辉光被跳过。 */
    fun burstFromScreen(rawX: Float, rawY: Float, onEnd: (() -> Unit)? = null): Boolean {
        if (width <= 0 || height <= 0) return false
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        cx = rawX - loc[0]
        cy = rawY - loc[1]
        this.onEnd = onEnd
        animator?.cancel()
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = burstDurationMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
        return true
    }

    /** 取消辉光并复位。进设置、中途滑出/被抢断、BAND 切走时都应调用，防止整条 BAND 残留白亮。 */
    fun reset() {
        animator?.cancel()
        animator = null
        onEnd = null
        progress = 0f
        invalidate()
    }

    /** 松手：把剩余辉光在 fillMs 内补齐，播完一帧整白后回调 onDone（用于进设置）。 */
    fun finishBurst(onDone: (() -> Unit)? = null, fillMs: Long = 120L) {
        if (width <= 0 || height <= 0) {
            onDone?.invoke()
            return
        }
        animator?.cancel()
        if (progress >= 1f || fillMs <= 0) {
            progress = 1f
            onEnd = onDone
            invalidate()
            return
        }
        onEnd = onDone
        animator = ValueAnimator.ofFloat(progress, 1f).apply {
            duration = fillMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f || width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (progress >= 1f) {
            canvas.drawColor(Color.WHITE)
            onEnd?.let { it(); onEnd = null }
            return
        }
        val maxR = Math.max(
            Math.hypot(cx.toDouble(), cy.toDouble()),
            Math.hypot((w - cx).toDouble(), (h - cy).toDouble())
        ).toFloat()
        val radius = (maxR * progress).coerceAtLeast(1f)
        glowPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.WHITE, Color.argb(0, 255, 255, 255)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, glowPaint)
    }
}

