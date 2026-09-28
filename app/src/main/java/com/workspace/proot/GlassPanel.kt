package com.workspace.proot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout

/**
 * 快捷启动抽屉「液态玻璃」面板（5.6.0，纯视觉层，与窗口结构/时序/贴边/拖拽零耦合）。
 *
 * GlassPanelDrawable —— 玻璃底全部绘制，形状与原 GradientDrawable.cornerRadii 一致
 * （顶角 20dp、底角 0 贴边）：
 *  ① 高透明深色垂直渐变底（顶 ~76% → 底 ~90% 不透明度阶梯）：背景透得见（玻璃是透的），
 *     白字在亮色桌面上仍可读；
 *  ② 顶边/侧边折射高光描边：1.6dp 竖直渐变 stroke，顶部 ~55% 白向下衰减、两侧留 ~6% 淡线
 *     ——光沿玻璃轮廓行走，顶角最亮；
 *  ③ 顶角径向聚光（光绕过圆角的包裹感）+ 顶内 30dp 高光带（玻璃厚度感）；
 *  ④ 流光扫过：弹出后与手柄按下各触发一次（非永动，不常驻重绘）；
 *  ⑤ 手指跟随光斑：按下即现、抬手即灭。
 *
 * 高光的光角/衰减/渐变色值对标主流 HTML/SVG 滤镜仿液态玻璃配方
 * （feSpecularLighting 顶光 135°、边缘 falloff、feGaussianBlur 磨砂底的观感），
 * 以 Canvas 渐变复刻其光照数学；背景物理折射为 iOS 系统级能力、安卓悬浮窗无采样权限，不做。
 */
internal class GlassPanelDrawable(private val density: Float) : Drawable() {

    private val cornerR = 20f * density
    private val glossH = 30f * density
    private val sheenHalf = 90f * density
    private val touchR = 110f * density

    private val path = Path()
    private val tmp = RectF()
    private val sheenMatrix = Matrix()
    private val touchMatrix = Matrix()

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 流光中心 x（面板坐标）；NaN = 关闭。 */
    private var sheenX = Float.NaN
    private var touchOn = false
    private var touchX = 0f
    private var touchY = 0f
    private var sheenAnimator: ValueAnimator? = null

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        tmp.set(b)
        path.reset()
        path.addRoundRect(
            tmp,
            floatArrayOf(cornerR, cornerR, cornerR, cornerR, 0f, 0f, 0f, 0f),
            Path.Direction.CW
        )
        val h = b.height().toFloat()
        basePaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xBE191D25.toInt(), 0xD114181F.toInt(), 0xE610131A.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        glossPaint.shader = LinearGradient(
            0f, 0f, 0f, glossH,
            intArrayOf(0x33FFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP
        )
        rimPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x8CFFFFFF.toInt(), 0x59FFFFFF.toInt(), 0x26FFFFFF, 0x10FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.05f, 0.35f, 0.95f, 1f),
            Shader.TileMode.CLAMP
        )
        cornerPaint.shader = RadialGradient(
            cornerR, cornerR, cornerR * 1.5f,
            intArrayOf(0x40FFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP
        )
        sheenPaint.shader = LinearGradient(
            -sheenHalf, 0f, sheenHalf, 0f,
            intArrayOf(0x00FFFFFF, 0x16FFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP
        )
        touchPaint.shader = RadialGradient(
            0f, 0f, touchR,
            intArrayOf(0x29FFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.clipPath(path)
        // ① 玻璃底（渐变 = 顶透底实，背景透得见）
        canvas.drawRect(b, basePaint)
        // ③ 顶内高光带
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + glossH, glossPaint)
        // ④ 流光
        if (!sheenX.isNaN()) {
            sheenMatrix.setTranslate(sheenX, 0f)
            sheenPaint.shader?.setLocalMatrix(sheenMatrix)
            canvas.drawRect(b, sheenPaint)
        }
        // ⑤ 手指光斑
        if (touchOn) {
            touchMatrix.setTranslate(touchX, touchY)
            touchPaint.shader?.setLocalMatrix(touchMatrix)
            canvas.drawRect(b, touchPaint)
        }
        canvas.restore()
        // ② 折射高光描边（跨边界的外半压在背景上，读作玻璃厚度）
        canvas.drawPath(path, rimPaint)
        // ③ 顶角聚光
        canvas.save()
        canvas.clipPath(path)
        canvas.drawCircle(cornerR, cornerR, cornerR * 1.5f, cornerPaint)
        canvas.drawCircle(b.width() - cornerR, cornerR, cornerR * 1.5f, cornerPaint)
        canvas.restore()
    }

    /** 流光扫过一次（弹出后 / 手柄按下触发）。 */
    fun startSheen() {
        if (bounds.isEmpty) return
        sheenAnimator?.cancel()
        val w = bounds.width().toFloat()
        sheenAnimator = ValueAnimator.ofFloat(-sheenHalf, w + sheenHalf).apply {
            duration = 720
            interpolator = LinearInterpolator()
            addUpdateListener {
                sheenX = it.animatedValue as Float
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    sheenX = Float.NaN
                    invalidateSelf()
                }
            })
            start()
        }
    }

    fun setTouch(x: Float, y: Float) {
        touchOn = true
        touchX = x
        touchY = y
        invalidateSelf()
    }

    fun clearTouch() {
        if (!touchOn) return
        touchOn = false
        invalidateSelf()
    }

    /** 抽屉关闭时停掉动画，不留悬挂的 animator。 */
    fun cancelAnimations() {
        sheenAnimator?.cancel()
        sheenAnimator = null
        sheenX = Float.NaN
        touchOn = false
    }

    override fun setAlpha(alpha: Int) {
        // 整体透明度由 View.alpha 控制（弹出淡入），drawable 自身 alpha 契约不使用
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        rimPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 玻璃面板视图：onInterceptTouchEvent 只观察不消费——把落点交给光斑，永远返回 false，
 * 行点击 / 手柄拖拽 / 滚动等全部子交互与改造前逐项一致（观察者模式，零行为影响）。
 */
internal class GlassPanelView(context: Context) : LinearLayout(context) {

    var glass: GlassPanelDrawable? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val g = glass
        if (g != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> g.setTouch(ev.x, ev.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> g.clearTouch()
            }
        }
        return false
    }
}
