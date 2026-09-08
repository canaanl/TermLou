package com.workspace.proot

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 独立纯视觉组件：不参与 wheel 任何滚动/吸附/点击逻辑，无触摸行为。
 * 唯一职责：根据"当前几层轮盘"把框高动画到矮/高两档。
 */
class WheelLevelFrame(context: Context) : View(context) {

    companion object {
        val FRAME_COLOR = Color.WHITE
        const val STROKE_DP = 2f
        const val BLUR_DP = 1f
        /** 辉光在框内四周需要的活动范围（上/下/左/右），供 onDraw 外溢不裁切。 */
        const val PAD_DP = 12f
        const val OPEN_MS = 220L
        const val CLOSE_MS = 180L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * resources.displayMetrics.density
        color = FRAME_COLOR
        maskFilter = BlurMaskFilter(BLUR_DP * resources.displayMetrics.density, BlurMaskFilter.Blur.NORMAL)
    }
    private val cornerRadius = ButtonStyle.CORNER_RADIUS_DP * resources.displayMetrics.density

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    private var frameH = 1f
    private var animator: ValueAnimator? = null
    private var closing = false

    fun setLevel(level: Int, cardH: Int, animate: Boolean = true) {
        if (closing) return
        val target = (if (level >= 2) cardH * 2 else cardH).toFloat()
        if (target <= 0f) return
        animator?.cancel()
        if (!animate || Math.abs(frameH - target) < 0.5f) {
            frameH = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(frameH, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                frameH = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 与 wheelPanel 进/出动画用相同参数做镜像，保证显隐完全同步。 */
    fun setOpen(open: Boolean, slidePx: Float) {
        animate().cancel()
        if (open) {
            closing = false
            visibility = View.VISIBLE
            translationY = slidePx
            alpha = 0f
            animate().translationY(0f).alpha(1f).setDuration(OPEN_MS)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            if (visibility != View.VISIBLE) {
                closing = false
                visibility = View.GONE
                translationY = 0f
                alpha = 1f
                return
            }
            closing = true
            animate().translationY(slidePx).alpha(0f).setDuration(CLOSE_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    visibility = View.GONE
                    translationY = 0f
                    alpha = 1f
                    closing = false
                }.start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || frameH <= 0f) return
        val pad = PAD_DP * resources.displayMetrics.density
        val inset = paint.strokeWidth / 2f
        val left = pad + inset
        val right = width - pad - inset
        val bottom = height - inset
        val top = (bottom - frameH).coerceAtLeast(pad + inset)
        canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, paint)
    }
}