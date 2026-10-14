package com.workspace.proot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * 5.9.49：状态栏**块①**（特征字）的**立方体转动**切换动画。
 *
 * ## 效果
 *
 * 切换页签时，整块像一个小立方体绕竖轴转 90°：
 * 旧词贴在正面转出去，新词从侧面转进来。
 *
 * ```
 * 第 0 帧            中段（转了约一半）        末帧
 * ┌──────────┐      ┌──────────┐          ┌──────────┐
 * │Terminal  │  →   │  ╲╱      │     →    │Notes     │
 * └──────────┘      └──────────┘          └──────────┘
 *                   两个面在棱上相接
 * ```
 *
 * ## 为什么块① 适合做立方体
 *
 * 它的**宽度是固定的**（[StatusController.measureTabWidth] 取五个词里最宽的），
 * 天然就是一个"方块"的尺寸 —— 不用额外定义方块多大。而且它只放一个词，
 * 转起来读得清。
 *
 * ## 与页面滑动方向一致
 *
 * `MainActivity.animateTo(fromRight)` 在切到后面的页签时让**新页面从右边进来**
 * （`show.translationX = +w` 归 0），旧页面向左退出。
 * 所以立方体也必须让**新词从右侧面转进来** —— 视觉上与页面同向。
 * 切到前面的页签反之（新页面从左边进来，新词从左侧面转进来）。
 *
 * ⚠ 5.9.52 修的就是这里：实现给的是 `targetAngle = +90`，
 * 而 +90 的新面法线指向 **−X（左侧面）** —— 新词从左边来，与页面正好相反。
 * 现改为 `targetAngle = -90`（新面在右侧面）。
 *
 * ## 面的角度约定（`Camera.rotateY`，正方向按右手法则）
 *
 * | 角度 | 法线指向 | 是立方体的哪一面 |
 * |---|---|---|
 * | `0` | +Z（朝观察者） | 正面 |
 * | `+90` | **+X（右）** | 右侧面 |
 * | `−90` | **−X（左）** | 左侧面 |
 *
 * 写这段动画时每次都要现推一遍这张表，很容易推反 —— 所以下面 [setTabName] 与
 * [onDraw] 附近都写了推导过程，`newBase` 也按 `targetAngle` 的符号推导而非手写死值。
 *
 * ## 两个"手感旋钮"（要调手感就调这两个）
 *
 * - [CUBE_DEPTH_RATIO]：方块有多"方"。越大越有立体感，但正面会因透视放大、
 *   在方块边缘被裁掉。取 1/3 是折中。
 * - [CAMERA_Z_INCHES]：透视强度。越**接近 0** 透视越强（越夸张）；
 *   越**负**越接近正交投影（越平）。安卓默认是 -8。
 *
 * 这两个数我看不到实际效果，只能凭经验给 —— 装机后觉得"太夸张"或"太扁"，
 * 就调这两个。
 */
class TabLabelCubeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(UiTokens.TEXT_COMPACT)
        typeface = Typeface.DEFAULT
    }
    private val camera = Camera().apply {
        setLocation(0f, 0f, -CAMERA_Z_INCHES)
    }
    private val matrix = Matrix()

    private var accent = 0xFFFFFFFF.toInt()

    /** 静止时显示的字。 */
    private var current = ""

    /** 翻转中的两个面：从 [from] 转到 [to]。 */
    private var from = ""
    private var to = ""

    /** 转动角度（度）：0 = 正面朝前。切后面的页签走 0 → −90（新面在右侧面），切前面的走 0 → +90。 */
    private var angle = 0f

    /** 转动中每帧都把角度收敛到这个值。 */
    private var targetAngle = 0f

    private var animating = false
    private var animator: ValueAnimator? = null

    fun setAccent(color: Int) {
        accent = color
        invalidate()
    }

    /**
     * 切换特征字。
     *
     * @param name 新词（Terminal/Notes/Files/Network/Settings）
     * @param animate 是否放翻转动画。切页签时 true；同页签刷新时 false。
     * @param toLeft 页面是否向左滑（= 切到后面的页签），决定立方体往哪边转。
     */
    fun setTabName(name: String, animate: Boolean, toLeft: Boolean = true) {
        // ⚠ 名字没变 → **直接返回，绝不取消在途动画**。
        //
        // 这是"网络页看不到动画"的根因：切到网络页时 refreshStatusBar() 会被调用两次
        // （showTab 一次、网络页自己的刷新再一次）。第二次名字没变，而旧写法把
        // `animator.cancel()` 放在"名字相同"的分支里 —— 等于把刚起步的动画当场掐死。
        // 名字没变时的正确行为是"什么都不做"。
        if (name == current) return

        // 首次建立：没有"旧词"可转，直接落定
        if (current.isEmpty() || !animate) {
            animator?.cancel()
            animator = null
            animating = false
            angle = 0f
            targetAngle = 0f
            current = name
            from = name
            to = name
            invalidate()
            return
        }

        // 换词：掐掉在途的那次（连点时不留残影），开新的
        animator?.cancel()
        from = current
        to = name
        current = name
        angle = 0f
        // 5.9.52 修方向反了：原来这里是 +90，新词实际从**左侧面**转进来，
        // 而 animateTo(fromRight=true) 让新页面从**右边**进来 —— 两者相反。
        // 改成 -90：新词起始于 +90° 面（法线指向 +X = 右侧面），由右转正，与页面同向。
        targetAngle = if (toLeft) -90f else 90f
        animating = true

        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            // 先快后慢：转到位时"稳一下"，比匀速更像一个实心的方块停下来
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                angle = (it.animatedValue as Float) * targetAngle
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 必须复位：否则 animating 永远为 true，onDraw 会一直走两个面的分支
                    animating = false
                    angle = 0f
                    animator = null
                    invalidate()
                }
            })
        }
        animator = a
        a.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 宽度由外层写死（StatusController.measureTabWidth()），按 spec 原样解
        val w = resolveSize(measuredWidth, widthMeasureSpec)
        val textH = (textPaint.descent() - textPaint.ascent()).toInt()
        val h = resolveSize(textH + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (!animating) {
            drawFace(canvas, current, 0f)
            return
        }
        // 面转出去时会因透视变大、溢出方块 —— 裁在方块里，看起来才像"一个方块在转"
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        // 旧面：从正面转出去；新面：与它相差 90°（贴在立方体的侧面上）
        //   向左滑（切后面）：新页面从**右**边进来 → 新面必须在**右**侧面（+90），随 angle 转回正面
        //   向右滑（切前面）：新页面从**左**边进来 → 新面在**左**侧面（-90）
        // 5.9.52：原注释与实现在这一段是反的（newBase 取 -90 却写成"右侧面"），
        // 现按 [targetAngle] 的符号推导，不再手写死值。
        val newBase = if (targetAngle > 0f) -90f else 90f
        drawFace(canvas, to, angle + newBase)
        drawFace(canvas, from, angle)
        canvas.restore()
    }

    /**
     * 画一个面。
     *
     * @param deg 这个面相对正面的角度：0 = 正对你，±90 = 侧对（看不见）。
     */
    private fun drawFace(canvas: Canvas, text: String, deg: Float) {
        // 转离视线越远越暗 —— 不压暗就像两张纸片交叉，没有立体感
        val shade = 1f - SHADE_DEPTH * (abs(deg) / 90f).coerceIn(0f, 1f)

        val cx = width / 2f
        val cy = height / 2f
        matrix.reset()
        camera.save()
        camera.rotateY(deg)
        // 沿这个面自己的法线推出去半个立方体深 —— 这样两个面在"棱"上相接，
        // 而不是穿过中心交叉（那是"翻卡片"，不是立方体）
        camera.translate(0f, 0f, -cubeDepth())
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-cx, -cy)
        matrix.postTranslate(cx, cy)

        canvas.save()
        canvas.concat(matrix)
        textPaint.color = shade(accent, shade)
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, paddingLeft.toFloat(), baseline, textPaint)
        canvas.restore()
    }

    /** 立方体深度（px）：见 [CUBE_DEPTH_RATIO]。 */
    private fun cubeDepth(): Float = width * CUBE_DEPTH_RATIO

    /** 按系数压暗一个颜色（保持不透明）。 */
    private fun shade(color: Int, f: Float): Int {
        val c = f.coerceIn(0f, 1f)
        val r = (((color shr 16) and 0xFF) * c).toInt()
        val g = (((color shr 8) and 0xFF) * c).toInt()
        val b = ((color and 0xFF) * c).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    companion object {
        /** 转动时长：比切页动画（250ms）略短，让"方块先停稳"、页面随后到位。 */
        const val DURATION_MS = 220L

        /** 方块深度占块宽的比例（旋钮，见类 KDoc）。 */
        private const val CUBE_DEPTH_RATIO = 1f / 3f

        /** 相机距离（英寸，旋钮，见类 KDoc）。越负越平；安卓默认 -8。 */
        private const val CAMERA_Z_INCHES = 10f

        /** 转到侧面时压暗多少（0 = 不压暗，1 = 全黑）。 */
        private const val SHADE_DEPTH = 0.55f
    }
}
