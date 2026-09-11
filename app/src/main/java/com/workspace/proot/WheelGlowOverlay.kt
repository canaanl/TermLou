package com.workspace.proot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * wheel 长按特效叠加层：从长按点开始发亮，波纹扩散至整条 BAND
 * （占位双层高的整个范围，无论当前单层还是双层）。
 * 不拦截触摸；辉光播完后回调 onEnd（用于打开 wheel 设置）。
 */
class WheelGlowOverlay(context: Context) : View(context) {

    /** 按住 1s 蓄力后从手指下方扩散至罩满整条 BAND 的时长。 */
    private val burstDurationMs = 1000L
    /** 松手补齐的目标进度：只到亮白 Blend，不画不透明白帧，收尾不突兀。 */
    private val settleProgress = 0.92f
    /** 松手补齐的速率：全部走完约 500ms，按剩余比例折算。 */
    private val fillMsPerUnit = 500L

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

    /** 松手：把剩余辉光线性补齐到 0.92（永不画整白帧），到位回调 onDone（用于进设置）。
     * 匀速无速率跳变 + 时长按剩余比例，到位即进设置，收尾从"弹白+硬切"变成"柔光渐满→设置滑入"。 */
    fun finishBurst(onDone: (() -> Unit)? = null) {
        if (width <= 0 || height <= 0) {
            onDone?.invoke()
            return
        }
        animator?.cancel()
        if (progress >= settleProgress) {
            progress = settleProgress
            invalidate()
            onDone?.invoke()
            return
        }
        onEnd = null
        animator = ValueAnimator.ofFloat(progress, settleProgress).apply {
            duration = ((1f - progress) * fillMsPerUnit).toLong().coerceAtLeast(80L)
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onDone?.invoke()
                }
            })
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

