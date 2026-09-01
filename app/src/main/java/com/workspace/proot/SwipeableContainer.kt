package com.workspace.proot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

class SwipeableContainer(context: Context) : FrameLayout(context) {

    private var startX = 0f
    private var startY = 0f
    private var swiped = false
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private val shortThreshold by lazy { (50 * resources.displayMetrics.density) }
    private val longThreshold by lazy { (150 * resources.displayMetrics.density) }

    var onSwipeLeft: (() -> Unit)? = null
    var onLongSwipeLeft: (() -> Unit)? = null
    var onClickPassthrough: ((MotionEvent) -> Unit)? = null

    private val swipeDrawable = SwipeBorderDrawable()

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.rawX
                startY = ev.rawY
                swiped = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = startX - ev.rawX
                val dy = Math.abs(ev.rawY - startY)
                if (dx > touchSlop && Math.abs(dx) > dy) {
                    swiped = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = startX - ev.rawX
                if (dx > 0) {
                    swipeDrawable.setProgress(
                        dx / shortThreshold,
                        (dx - shortThreshold) / (longThreshold - shortThreshold)
                    )
                    if (foreground !== swipeDrawable) foreground = swipeDrawable
                    swipeDrawable.invalidateSelf()
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = startX - ev.rawX
                if (dx > longThreshold) {
                    onLongSwipeLeft?.invoke()
                } else if (dx > shortThreshold) {
                    onSwipeLeft?.invoke()
                } else {
                    onClickPassthrough?.invoke(ev)
                }
                foreground = null
            }
            MotionEvent.ACTION_CANCEL -> {
                foreground = null
            }
        }
        return true
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private class SwipeBorderDrawable : Drawable() {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private var sweep = 0f
        private var bright = 0f

        fun setProgress(sweep: Float, bright: Float) {
            this.sweep = sweep.coerceIn(0f, 1f)
            this.bright = bright.coerceIn(0f, 1f)
        }

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            if (w <= 0f) return
            val right = bounds.right.toFloat()
            val top = bounds.top.toFloat()
            val bottom = bounds.bottom.toFloat()

            // 整栏渐变填充：从右缘向左推进，进度头在右（纯渐变，无边框无竖线）
            val lightLen = w * sweep
            if (lightLen > 0f) {
                val start = right - lightLen
                val alpha = (20 + 80 * sweep).toInt().coerceIn(0, 200)
                drawFill(canvas, start, right, top, bottom, 45, 125, 70, alpha)
            }

            // 二段：同色叠加变亮 → 换为主题黄，盖在一段绿之上
            val strongLen = w * bright
            if (strongLen > 0f) {
                val start = right - strongLen
                val alpha = (180 + 75 * bright).toInt().coerceIn(0, 255)
                drawFill(canvas, start, right, top, bottom, 255, 213, 79, alpha)
            }

            fillPaint.shader = null
        }

        private fun drawFill(
            canvas: Canvas,
            start: Float,
            end: Float,
            top: Float,
            bottom: Float,
            r: Int,
            g: Int,
            b: Int,
            alpha: Int
        ) {
            fillPaint.shader = LinearGradient(
                start, top, end, top,
                intArrayOf(
                    Color.argb(0, r, g, b),
                    Color.argb(alpha, r, g, b),
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(start, top, end, bottom, fillPaint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}
