package com.workspace.proot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 5.9.48：状态栏**块①**（特征字）的激光打标切换动画。
 *
 * ## 效果
 *
 * 切换页签时，旧词被一道**白色激光**从左往右（或从右往左）扫掉，
 * 扫过的地方露出新词 —— 像激光打标把旧涂层烧掉、底下已经刻好新字。
 *
 * ```
 * 第 0 帧        中段（激光在 p 处）        末帧
 * ┌──────────┐  ┌──────────┐          ┌──────────┐
 * │Terminal  │  │Ter▌es    │          │Notes     │
 * └──────────┘  └──────────┘          └──────────┘
 *                 ↑ 只有这道白光在动
 * ```
 *
 * ## 三条硬约束（用户明确要求）
 *
 * 1. **两个词都左对齐、永不平移** —— 动的只有激光，字的位置纹丝不动。
 *    中段会看到"半个旧词 + 半个新词"（那是抹除的必然结果），
 *    靠白色激光带**盖住接缝**，让它读起来是"光扫过去、字就变了"。
 * 2. **扫动方向 = 页面滑动方向**：切到后面的页签时内容向左滑、新页面从右边进来，
 *    所以激光也**从右往左**扫（新词先从右侧出现）；切到前面的页签反之。
 * 3. 连续快速切页签时，新一次**掐掉**在途的那次，不留残影。
 *
 * ## 余晖
 *
 * 激光扫完后，亮带再淡出约 70ms —— "余温"。
 */
class LaserTabLabelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(UiTokens.TEXT_COMPACT)
        typeface = Typeface.DEFAULT
    }

    /** 激光带：中间白得发亮、两侧渐隐（不是一条硬边，那样不像光）。 */
    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 当前显示的字（动画静止时就是它）。 */
    private var current = ""

    /** 动画中的两个词：从 [from] 抹到 [to]。 */
    private var from = ""
    private var to = ""

    /** 扫描进度 0..1（0=最左、1=最右），动画结束后停在落点。 */
    private var laser = 1f

    /** true = 从右往左扫（切到后面的页签）。 */
    private var sweepingLeft = true

    /** 正在动画中（含余晖阶段）。 */
    private var animating = false

    /** 余晖强度 0..1：扫描阶段恒为 1，余晖阶段淡到 0。 */
    private var glow = 0f

    private var animator: ValueAnimator? = null

    private val laserHalfPx = dp(7f)

    private fun setAccentInternal(color: Int) {
        textPaint.color = color
    }

    fun setAccent(color: Int) {
        setAccentInternal(color)
        invalidate()
    }

    /**
     * 切换特征字。
     *
     * @param name 新词（Terminal/Notes/Files/Network/Settings）
     * @param animate 是否放激光动画。切页签时 true；初次建立/同页签刷新时 false。
     * @param toLeft 激光是否向左扫（= 页面是否向左滑，即切到后面的页签）。
     */
    fun setTabName(name: String, animate: Boolean, toLeft: Boolean = true) {
        // 不放动画的三种情况：明确要求不动画 / 首次建立（没有"旧词"可抹）/ 词没变（同页签刷新）
        if (!animate || current.isEmpty() || name == current) {
            animator?.cancel()
            animator = null
            animating = false
            glow = 0f
            current = name
            from = name
            to = name
            invalidate()
            return
        }
        // 连续快速切页签：新一次直接掐掉在途的那次
        animator?.cancel()

        from = current
        to = name
        current = name
        sweepingLeft = toLeft
        animating = true
        glow = 1f
        laser = if (toLeft) 1f else 0f

        val sweep = SWEEP_MS.toFloat()
        val a = ValueAnimator.ofFloat(0f, sweep + AFTERGLOW_MS.toFloat()).apply {
            duration = SWEEP_MS + AFTERGLOW_MS
            // 匀速：激光是"扫过去"，先快后慢会像"停了一下"，不像打标
            interpolator = LinearInterpolator()
            addUpdateListener {
                val ms = it.animatedValue as Float
                val sweepT = (ms / sweep).coerceIn(0f, 1f)
                laser = if (toLeft) 1f - sweepT else sweepT
                // 扫描阶段余晖恒满，扫完才开始淡出
                glow = if (ms <= sweep) 1f else (1f - (ms - sweep) / AFTERGLOW_MS.toFloat()).coerceIn(0f, 1f)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 必须复位：否则 animating 永远为 true，onDraw 会一直走裁剪分支
                    animating = false
                    glow = 0f
                    animator = null
                    invalidate()
                }
            })
        }
        animator = a
        a.start()
    }

    /**
     * 高度 = 字高（ascent→descent）+ 上下内距。
     *
     * 自绘控件**必须自己量**：父容器给的是 WRAP_CONTENT，View 的默认 onMeasure
     * 在 WRAP_CONTENT 下会把高度量成 0 —— 那样整条状态栏会被压扁。
     *
     * ⚠ 这里**不含** `includeFontPadding` 那套额外留白：它是给 TextView 排版的，
     * 我们自己按 ascent/descent 画基线，多出来的约 3.7dp 纯属白占高度。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 宽度由外层写死（StatusController.measureTabWidth()，五个词里最宽的）
        val w = resolveSize(measuredWidth, widthMeasureSpec)
        val textH = (textPaint.descent() - textPaint.ascent()).toInt()
        val h = resolveSize(textH + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        val x = paddingLeft.toFloat()

        if (animating) {
            // 刀口位置：laser 0 → 最左，1 → 最右
            val usable = (width - paddingLeft).toFloat()
            val cut = paddingLeft + laser * usable

            // 两个词都从**同一个左端**起画，谁也不平移 —— 只有刀口在动。
            //
            // 刀口**已经扫过**的那一侧显示**新**词，另一侧显示**旧**词：
            //   向左扫（切后面的页签）→ 已扫过的是**右**侧 → 右新左旧
            //   向右扫（切前面的页签）→ 已扫过的是**左**侧 → 左新右旧
            // 这一条写反了就会"从哪边扫，哪边还是旧字"，是最容易错的地方。
            val sweptSideIsRight = sweepingLeft
            val newLeft = paddingLeft.toFloat()
            val newRight = if (sweptSideIsRight) width.toFloat() else cut
            val oldLeft = if (sweptSideIsRight) cut else paddingLeft.toFloat()
            val oldRight = if (sweptSideIsRight) cut else width.toFloat()

            canvas.save()
            canvas.clipRect(newLeft, 0f, newRight, height.toFloat())
            canvas.drawText(to, x, baseline, textPaint)
            canvas.restore()

            canvas.save()
            canvas.clipRect(oldLeft, 0f, oldRight, height.toFloat())
            canvas.drawText(from, x, baseline, textPaint)
            canvas.restore()
        } else {
            canvas.drawText(current, x, baseline, textPaint)
        }

        if (glow > 0f) drawLaser(canvas)
    }

    /** 白色激光带：盖住接缝，让"半个旧词 + 半个新词"读不出来。 */
    private fun drawLaser(canvas: Canvas) {
        val usable = (width - paddingLeft).toFloat()
        val cut = paddingLeft + laser * usable
        // 余晖阶段带子略微散开（像余温扩散），扫描阶段是紧实的一道
        val scale = if (animating) 1f else AFTERGLOW_SCALE
        val half = laserHalfPx * scale

        laserPaint.shader = LinearGradient(
            cut - half, 0f, cut + half, 0f,
            intArrayOf(
                white(0f), white(ALPHA_EDGE * glow), white(ALPHA_CORE * glow),
                white(ALPHA_EDGE * glow), white(0f),
            ),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(cut - half, 0f, cut + half, height.toFloat(), laserPaint)
    }

    /** 激光带里每一个色标都是**白色**，只是透明度不同 —— 这样叠出来的才是"光"。 */
    private fun white(alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or 0x00FFFFFF

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    companion object {
        /** 扫描时长：与切页动画同长（MainActivity.animateTo 是 250ms），同进同出不打架。 */
        const val SWEEP_MS = 250L

        /** 余晖时长：扫完后亮带再淡出一下（"余温"）。 */
        const val AFTERGLOW_MS = 70L

        /** 余晖阶段带子散开的倍数（更宽更淡，像余温扩散）。 */
        private const val AFTERGLOW_SCALE = 1.8f

        private const val ALPHA_CORE = 0.95f
        private const val ALPHA_EDGE = 0.35f
    }
}
