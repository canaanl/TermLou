package com.workspace.proot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

class BraceMenu(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val braceW = 26f * density
    private val braceGap = 2f * density
    private val optW = 118f * density
    private val optH = 36f * density
    private val optGap = 4f * density
    private val corner = 8f * density

    private val braceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTokens.braceText
        textSize = 32f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTokens.chipSurface
    }
    private val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTokens.braceBlue
        textSize = 15f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTokens.braceRed
        textSize = 15f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val scrimPaint = Paint()

    private val handler = Handler(Looper.getMainLooper())

    private var anchorX = 0f
    private var anchorY = 0f
    private var rowHeight = 0f
    private var needsConfirm = false
    private var opt1Label: String = context.getString(R.string.file_export)
    private var opt2Label: String? = context.getString(R.string.file_delete)
    private var onOpt1: (() -> Unit)? = null
    private var onOpt2: (() -> Unit)? = null

    private val hasOpt2 get() = opt2Label != null

    private var l1Progress = 0f
    private var l2Progress = 0f
    private var inL2 = false
    private var blinkOn = true
    private var l1Anim: ValueAnimator? = null
    private var l2Anim: ValueAnimator? = null

    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            invalidate()
            handler.postDelayed(this, 400)
        }
    }

    private var moved = false
    private var downX = 0f
    private var downY = 0f

    private lateinit var l1Opt1: RectF
    private lateinit var l1Opt2: RectF
    private lateinit var l2Opt1: RectF
    private lateinit var l2Opt2: RectF

    fun show(
        host: FrameLayout,
        ay: Float,
        rh: Float,
        opt1Label: String,
        opt2Label: String?,
        confirm: Boolean,
        onOpt1: () -> Unit,
        onOpt2: () -> Unit
    ) {
        this.anchorY = ay
        this.rowHeight = rh
        this.opt1Label = opt1Label
        this.opt2Label = opt2Label
        this.needsConfirm = confirm && hasOpt2
        this.onOpt1 = onOpt1
        this.onOpt2 = onOpt2
        val margin = 4f * density
        val fullWidth = if (needsConfirm) 2 * braceW + 3 * braceGap + 2 * optW
                        else braceW + braceGap + optW
        val maxLeft = (host.width - fullWidth - margin).coerceAtLeast(margin)
        this.anchorX = ((host.width - fullWidth) / 2f).coerceIn(margin, maxLeft)
        computeRects(host.height.toFloat())
        inL2 = false
        l2Progress = 0f
        (parent as? FrameLayout)?.removeView(this)
        host.addView(this, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        bringToFront()
        animateL1To(1f)
    }

    private fun computeRects(hostH: Float) {
        val menuH = if (hasOpt2) optH * 2 + optGap else optH
        var menuTop = anchorY + rowHeight / 2f - menuH / 2f
        menuTop = menuTop.coerceIn(0f, (hostH - menuH).coerceAtLeast(0f))
        val optLeft = anchorX + braceW + braceGap
        l1Opt1 = RectF(optLeft, menuTop, optLeft + optW, menuTop + optH)
        l1Opt2 = if (hasOpt2) {
            RectF(optLeft, menuTop + optH + optGap, optLeft + optW, menuTop + 2 * optH + optGap)
        } else {
            RectF()
        }
        val c2 = l1Opt2.centerY()
        val b2Top = c2 - (optH * 2 + optGap) / 2f
        val opt2Left = l1Opt2.right + braceGap + braceW + braceGap
        l2Opt1 = RectF(opt2Left, b2Top, opt2Left + optW, b2Top + optH)
        l2Opt2 = RectF(opt2Left, b2Top + optH + optGap, opt2Left + optW, b2Top + 2 * optH + optGap)
    }

    private fun animateL1To(target: Float) {
        l1Anim?.cancel()
        val anim = ValueAnimator.ofFloat(l1Progress, target)
        anim.duration = 220
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener {
            l1Progress = it.animatedValue as Float
            invalidate()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (l1Progress <= 0.01f) removeFromHost()
            }
        })
        l1Anim = anim
        anim.start()
    }

    private fun animateL2To(target: Float) {
        l2Anim?.cancel()
        val anim = ValueAnimator.ofFloat(l2Progress, target)
        anim.duration = 200
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener {
            l2Progress = it.animatedValue as Float
            invalidate()
        }
        l2Anim = anim
        anim.start()
    }

    private fun enterL2() {
        inL2 = true
        startBlink()
        animateL2To(1f)
    }

    private fun retractL2() {
        inL2 = false
        stopBlink()
        animateL2To(0f)
    }

    private fun performOpt1() {
        val cb = onOpt1
        dismissAll()
        cb?.invoke()
    }

    private fun performOpt2() {
        val cb = onOpt2
        dismissAll()
        cb?.invoke()
    }

    private fun dismissAll() {
        inL2 = false
        stopBlink()
        animateL2To(0f)
        animateL1To(0f)
    }

    private fun removeFromHost() {
        handler.removeCallbacksAndMessages(null)
        (parent as? FrameLayout)?.removeView(this)
    }

    private fun startBlink() {
        blinkOn = true
        handler.removeCallbacks(blinkRunnable)
        handler.postDelayed(blinkRunnable, 400)
    }

    private fun stopBlink() {
        handler.removeCallbacks(blinkRunnable)
        blinkOn = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (l1Progress <= 0.01f) return
        drawScrim(canvas)
        drawL1(canvas)
        if (inL2 || l2Progress > 0.01f) drawL2(canvas)
    }

    private fun drawScrim(canvas: Canvas) {
        val fullWidth = if (needsConfirm) 2 * braceW + 3 * braceGap + 2 * optW
                        else braceW + braceGap + optW
        val cx = anchorX + fullWidth / 2f
        val cy = anchorY + rowHeight / 2f
        val radius = maxOf(width, height).coerceAtLeast(1).toFloat() * 0.9f
        scrimPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(UiTokens.scrimStart, 0x00000000),
            null, Shader.TileMode.CLAMP
        )
        scrimPaint.alpha = (255 * l1Progress).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        scrimPaint.shader = null
    }

    private fun drawL1(canvas: Canvas) {
        val p = l1Progress
        val braceRect = RectF(
            anchorX,
            l1Opt1.top,
            anchorX + braceW * p,
            if (hasOpt2) l1Opt2.bottom else l1Opt1.bottom
        )
        drawBrace(canvas, braceRect, p)

        val opt1Left = anchorX + (l1Opt1.left - anchorX) * p
        val opt1Right = anchorX + (l1Opt1.right - anchorX) * p
        drawChip(canvas, RectF(opt1Left, l1Opt1.top, opt1Right, l1Opt1.bottom), p)
        drawLabel(canvas, opt1Label, RectF(opt1Left, l1Opt1.top, opt1Right, l1Opt1.bottom), bluePaint, p)

        if (hasOpt2) {
            val opt2Left = anchorX + (l1Opt2.left - anchorX) * p
            val opt2Right = anchorX + (l1Opt2.right - anchorX) * p
            val opt2Rect = RectF(opt2Left, l1Opt2.top, opt2Right, l1Opt2.bottom)
            drawChip(canvas, opt2Rect, p)
            if (inL2) {
                val blinkAlpha = if (blinkOn) 1f else 0.25f
                drawLabel(canvas, context.getString(R.string.brace_confirm), opt2Rect, redPaint, p * blinkAlpha)
            } else {
                drawLabel(canvas, opt2Label ?: "", opt2Rect, redPaint, p)
            }
        }
    }

    private fun drawL2(canvas: Canvas) {
        val p = l2Progress
        if (p <= 0.01f) return
        val origin = l1Opt2.right
        val braceRect = RectF(
            origin,
            l2Opt1.top,
            origin + braceW * p,
            l2Opt2.bottom
        )
        drawBrace(canvas, braceRect, p)

        val opt1Left = origin + (l2Opt1.left - origin) * p
        val opt1Right = origin + (l2Opt1.right - origin) * p
        drawChip(canvas, RectF(opt1Left, l2Opt1.top, opt1Right, l2Opt1.bottom), p)
        drawLabel(canvas, context.getString(R.string.brace_oops), RectF(opt1Left, l2Opt1.top, opt1Right, l2Opt1.bottom), bluePaint, p)

        val opt2Left = origin + (l2Opt2.left - origin) * p
        val opt2Right = origin + (l2Opt2.right - origin) * p
        drawChip(canvas, RectF(opt2Left, l2Opt2.top, opt2Right, l2Opt2.bottom), p)
        drawLabel(canvas, context.getString(R.string.brace_confirm_btn), RectF(opt2Left, l2Opt2.top, opt2Right, l2Opt2.bottom), redPaint, p)
    }

    private fun drawBrace(canvas: Canvas, rect: RectF, alpha: Float) {
        if (alpha <= 0.01f) return
        val p = Paint(braceTextPaint).apply { this.alpha = (255 * alpha).toInt() }
        val fm = p.fontMetrics
        val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText("{", rect.centerX(), baseline, p)
    }

    private fun drawChip(canvas: Canvas, rect: RectF, alpha: Float) {
        val p = Paint(chipPaint).apply { this.alpha = (alpha * 255).toInt() }
        canvas.drawRoundRect(rect, corner, corner, p)
    }

    private fun drawLabel(canvas: Canvas, text: String, rect: RectF, base: Paint, alpha: Float) {
        val p = Paint(base).apply { this.alpha = (255 * alpha).toInt() }
        val fm = p.fontMetrics
        val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, rect.centerX(), baseline, p)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > 24 * density || abs(event.y - downY) > 24 * density) {
                    moved = true
                    dismissAll()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) handleTap(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        if (l1Progress < 0.9f) return
        if (inL2) {
            if (l2Opt1.contains(x, y)) {
                retractL2()
            } else if (l2Opt2.contains(x, y)) {
                performOpt2()
            } else if (l2Progress > 0.9f) {
                retractL2()
            }
            return
        }
        when {
            l1Opt1.contains(x, y) -> performOpt1()
            hasOpt2 && l1Opt2.contains(x, y) -> if (needsConfirm) enterL2() else performOpt2()
            else -> dismissAll()
        }
    }
}
