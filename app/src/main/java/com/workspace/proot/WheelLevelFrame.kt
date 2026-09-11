package com.workspace.proot

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 独立纯视觉组件：不参与 wheel 任何滚动/吸附/点击逻辑，无触摸行为。
 * 唯一职责：根据"当前几层轮盘"把框高动画到矮/高两档。
 */
class WheelLevelFrame(
    context: Context,
    private val boxWidthPx: Int,
    private val drawScrim: Boolean = true
) : View(context) {

    companion object {
        val FRAME_COLOR = Color.WHITE
        /** 框外霜膜颜色：冷灰微提亮（相对纯黑），带一点点毛玻璃味，中心洞口保持不动。 */
        val SCRIM_COLOR = Color.parseColor("#D62B2F36")
        const val STROKE_DP = 2f
        const val BLUR_DP = 1f
        /** 辉光在框内四周需要的活动范围（上/下/左/右），供 onDraw 外溢不裁切。 */
        const val PAD_DP = 12f
        const val OPEN_MS = 220L
        const val CLOSE_MS = 180L
        /** 霜噪点颗粒尺寸、透明度级数与 ARGB 高位移（ALPHA_8 只取 alpha 字节，颗粒感只做一点点）。 */
        const val GRAIN_PX = 64
        const val GRAIN_ALPHA_LEVELS = 37
        const val ALPHA_BYTE_SHIFT = 24
        /** 噪点随机种子（固定种子保证每次启动颗粒分布一致，不闪烁）。 */
        const val GRAIN_SEED = 7L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * resources.displayMetrics.density
        color = FRAME_COLOR
        maskFilter = BlurMaskFilter(BLUR_DP * resources.displayMetrics.density, BlurMaskFilter.Blur.NORMAL)
    }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SCRIM_COLOR
    }
    /** 霜膜噪点：一次性 64×64 随机颗粒平铺，极淡，只给遮罩区一点点毛玻璃质感。 */
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(makeGrain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val scrimBounds = RectF()
    private val scrimHole = RectF()
    private val holePath = Path()
    private val cornerRadius = ButtonStyle.CORNER_RADIUS_DP * resources.displayMetrics.density

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    private var frameH = 1f
    private var animator: ValueAnimator? = null
    private var closing = false

    private fun makeGrain(): Bitmap {
        val bmp = Bitmap.createBitmap(GRAIN_PX, GRAIN_PX, Bitmap.Config.ALPHA_8)
        val rnd = java.util.Random(GRAIN_SEED)
        val px = IntArray(GRAIN_PX * GRAIN_PX) { (rnd.nextInt(GRAIN_ALPHA_LEVELS) shl ALPHA_BYTE_SHIFT) }
        bmp.setPixels(px, 0, GRAIN_PX, 0, 0, GRAIN_PX, GRAIN_PX)
        return bmp
    }

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

    /** 与 wheelPanel 进/出动画用相同参数做镜像，保证显隐完全同步。仅淡入淡出，位置跟随布局。 */
    fun setOpen(open: Boolean) {
        animate().cancel()
        if (open) {
            closing = false
            visibility = View.VISIBLE
            translationY = 0f
            alpha = 0f
            animate().alpha(1f).setDuration(OPEN_MS)
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
            animate().alpha(0f).setDuration(CLOSE_MS)
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
        val inset = paint.strokeWidth / 2f
        val bandTop = (height - frameH).coerceAtLeast(0f)
        val bandBottom = height.toFloat()
        val boxLeft = (width - boxWidthPx) / 2f
        val boxRight = boxLeft + boxWidthPx

        val sLeft = boxLeft + inset
        val sRight = boxRight - inset
        val sTop = (bandTop - inset).coerceAtLeast(inset)
        val sBottom = bandBottom - inset

        scrimBounds.set(0f, bandTop, width.toFloat(), bandBottom)
        scrimHole.set(sLeft, sTop, sRight, sBottom)
        if (drawScrim) {
            holePath.rewind()
            if (scrimHole.width() > 0f && scrimHole.height() > 0f) {
                holePath.addRoundRect(scrimHole, cornerRadius, cornerRadius, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipRect(scrimBounds)
            canvas.clipOutPath(holePath)
            canvas.drawRect(scrimBounds, scrimPaint)
            canvas.drawRect(scrimBounds, grainPaint)
            canvas.restore()
        }

        canvas.drawRoundRect(sLeft, sTop, sRight, sBottom, cornerRadius, cornerRadius, paint)
    }
}