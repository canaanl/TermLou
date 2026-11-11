@file:Suppress("MagicNumber", "LongParameterList", "TooManyFunctions")

package com.workspace.proot

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/**
 * 笔记页右下角的水滴式 FAB 组（5.11.1）。
 *
 * 收起时只有一颗组图标；展开后是 **组 + 搜索 + 新建** 三颗。
 * 展开/收起不是简单的淡入淡出：
 *  - 主 FAB 轻微 squash & stretch；
 *  - 两颗小 FAB 从主 FAB 中心弹出，带 Overshoot 回弹；
 *  - 中间画一条随距离变细的“水滴颈部”，分离后消失/合并时重新长出来。
 *
 * 触摸：根布局是 MATCH_PARENT，但只有三颗 FAB 自己消费点击；
 * 空白区域的触摸会落到下面的遮罩/列表，不会挡住横滑切 Tab。
 */
class NotesFabMenu(
    context: Context,
    private val theme: ThemeColors,
    private val onToggle: () -> Unit,
    private val onSearch: () -> Unit,
    private val onNewNote: () -> Unit,
    private val onDigest: () -> Unit = {},
) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private val mainSize = (56 * d).toInt()
    /** 展开后的搜索/新建必须与收起时那颗完全同尺寸，不能小一号。 */
    private val miniSize = mainSize
    private val endMargin = (16 * d).toInt()
    private val mainBottom = (16 * d).toInt()
    private val gap = (10 * d).toInt()
    private val addBottom = mainBottom + mainSize + gap
    private val searchBottom = addBottom + miniSize + gap

    private val main: TextView
    private val add: TextView
    private val search: TextView

    /** 搜索模式下 ⌕ 变成聚合按钮；false = 普通搜索模式。 */
    private var digestMode = false

    /** 聚合按钮是否可用（有正文/待办命中）。 */
    private var digestAvailable = false

    /** 聚合图标（按 bounds 中心绘制，尺寸/颜色均由它决定）。 */
    private val digestIcon = DigestIconDrawable(context)

    private var progress = 0f
    private var addStart = 0f
    private var searchStart = 0f
    private var animator: ValueAnimator? = null

    /** 只表达“组是否展开”，动画中间值用 [progress]。 */
    var isExpanded: Boolean = false
        private set

    private val neckFace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.surface
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val neckShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorMath.mix(theme.surface, Color.BLACK, 0.42f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false

        main = makeFab(
            glyph = "☰",
            desc = context.getString(R.string.notes_menu_expand),
            size = mainSize,
            bottom = mainBottom,
            end = endMargin,
            onClick = {
                if (animator?.isRunning == true) return@makeFab
                onToggle()
            },
        )
        add = makeFab(
            glyph = "＋",
            desc = context.getString(R.string.notes_new),
            size = miniSize,
            bottom = addBottom,
            end = endMargin,
            onClick = onNewNote,
        ).apply {
            visibility = View.GONE
            alpha = 0f
        }
        search = makeFab(
            glyph = "⌕",
            desc = context.getString(R.string.notes_search_notes),
            size = miniSize,
            bottom = searchBottom,
            end = endMargin,
            onClick = {
                if (digestMode) {
                    if (digestAvailable) onDigest()
                } else {
                    onSearch()
                }
            },
        ).apply {
            visibility = View.GONE
            alpha = 0f
        }

        addView(add)
        addView(search)
        addView(main)
    }

    private fun makeFab(
        glyph: String,
        desc: String,
        size: Int,
        bottom: Int,
        end: Int,
        onClick: () -> Unit,
    ): TextView = NeuFab.create(context, theme, glyph, desc, onClick).apply {
        layoutParams = LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = end
            bottomMargin = bottom
        }
        textSize = UiTokens.TEXT_DISPLAY
    }

    /** 组 FAB 圆心（相对本 View，也就是相对内容区）。 */
    fun mainCenter(): PointF =
        if (main.width > 0 && main.height > 0) {
            PointF(
                main.left + main.width / 2f + main.translationX,
                main.top + main.height / 2f + main.translationY,
            )
        } else {
            PointF(
                width - endMargin - mainSize / 2f,
                height - mainBottom - mainSize / 2f,
            )
        }

    fun setExpanded(expanded: Boolean, animate: Boolean = true) {
        if (!animate) {
            setExpandedImmediate(expanded)
            return
        }
        if (isExpanded == expanded && animator?.isRunning != true) {
            applyProgress(if (expanded) 1f else 0f)
            return
        }
        isExpanded = expanded
        animator?.cancel()

        if (expanded) {
            add.visibility = View.VISIBLE
            search.visibility = View.VISIBLE
            prepareStarts()
            main.text = "×"
            main.contentDescription = context.getString(R.string.notes_menu_collapse)
            NeuFab.centerGlyph(main)
        }

        val from = progress
        val to = if (expanded) 1f else 0f
        val anim = ValueAnimator.ofFloat(from, to).apply {
            duration = if (expanded) EXPAND_MS else COLLAPSE_MS
            interpolator = if (expanded) OvershootInterpolator(1.55f) else DecelerateInterpolator()
            addUpdateListener {
                applyProgress(it.animatedValue as Float)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator !== animation) return
                    animator = null
                    applyProgress(to)
                    if (!expanded) {
                        add.visibility = View.GONE
                        search.visibility = View.GONE
                        main.text = "☰"
                        main.contentDescription = context.getString(R.string.notes_menu_expand)
                        NeuFab.centerGlyph(main)
                    }
                }
            })
        }
        animator = anim
        anim.start()
    }

    fun setExpandedImmediate(expanded: Boolean) {
        animator?.cancel()
        animator = null
        isExpanded = expanded
        prepareStarts()
        applyProgress(if (expanded) 1f else 0f)
        add.visibility = if (expanded) View.VISIBLE else View.GONE
        search.visibility = if (expanded) View.VISIBLE else View.GONE
        main.text = if (expanded) "×" else "☰"
        main.contentDescription = context.getString(
            if (expanded) R.string.notes_menu_collapse else R.string.notes_menu_expand
        )
        NeuFab.centerGlyph(main)
    }

    fun cancelAnimation() {
        animator?.cancel()
        animator = null
    }

    /**
     * 切换搜索 FAB 的语义（5.12.6）。
     *
     * - [enabled] false：恢复普通 ⌕，点击开关搜索；
     * - [enabled] true：变成聚合图标（前景 Drawable 居中绘制），点击进入聚合页；
     * - [available] false 时用禁用色（与 [FileBackFab] 同一套颜色机制）。
     *
     * 颜色规则与 [FileBackFab.renderState] 一致：
     * 启用 = [ThemeColors.primaryVisible]，禁用 = [NeuFab.disabledIconColor]。
     * 不再用 alpha 假装灰色，避免与展开动画的 alpha/scale 互相覆盖。
     */
    fun setDigestMode(enabled: Boolean, available: Boolean) {
        digestMode = enabled
        digestAvailable = enabled && available
        // 清掉上一次字形可能留下的墨迹补偿 padding。
        search.setPadding(0, 0, 0, 0)
        if (!enabled) {
            search.text = GLYPH_SEARCH
            search.foreground = null
            search.contentDescription = context.getString(R.string.notes_search_notes)
            search.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            search.setTextColor(theme.primaryVisible)
            search.isClickable = true
            search.alpha = 1f
            NeuFab.centerGlyph(search)
            return
        }
        // 空文本：NeuFab.centerGlyph 会提前返回，不会再动 padding。
        search.text = ""
        search.contentDescription = context.getString(R.string.notes_digest_action)
        digestIcon.color = if (digestAvailable) theme.primaryVisible else NeuFab.disabledIconColor(theme)
        search.foreground = digestIcon
        search.isClickable = digestAvailable
        search.alpha = 1f
    }

    /** 测试用：聚合按钮是否可用。 */
    internal fun digestAvailableForTest(): Boolean = digestAvailable

    /** 测试用：搜索/聚合那颗 FAB。 */
    internal fun searchFabForTest(): TextView = search

    /** 测试用：聚合图标颜色（未进入聚合模式时为 null）。 */
    internal fun digestIconColorForTest(): Int? =
        if (digestMode) digestIcon.color else null

    private fun prepareStarts() {
        val mc = mainCenter()
        addStart = mc.y - centerY(add)
        searchStart = mc.y - centerY(search)
    }

    private fun centerY(v: View): Float =
        if (v.height > 0) v.top + v.height / 2f else v.bottom - v.height / 2f

    private fun applyProgress(value: Float) {
        progress = value
        add.translationY = addStart * (1f - value)
        search.translationY = searchStart * (1f - value)
        val clamped = value.coerceIn(0f, 1f)
        add.alpha = clamped
        search.alpha = clamped
        add.scaleX = 0.2f + 0.8f * value
        add.scaleY = 0.2f + 0.8f * value
        search.scaleX = 0.2f + 0.8f * value
        search.scaleY = 0.2f + 0.8f * value
        val squash = sin(PI * clamped).toFloat()
        main.scaleX = 1f + 0.07f * squash
        main.scaleY = 1f - 0.11f * squash
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0.001f || progress >= 0.999f) return
        val neck = (1f - progress).coerceIn(0f, 1f)
        if (neck <= 0.02f) return
        val widthPx = miniSize * 0.82f * neck
        val mc = mainCenter()
        val ac = PointF(
            add.left + add.width / 2f + add.translationX,
            add.top + add.height / 2f + add.translationY,
        )
        val sc = PointF(
            search.left + search.width / 2f + search.translationX,
            search.top + search.height / 2f + search.translationY,
        )
        drawNeck(canvas, mc, ac, widthPx)
        drawNeck(canvas, ac, sc, widthPx)
    }

    private fun drawNeck(canvas: Canvas, a: PointF, b: PointF, widthPx: Float) {
        if (widthPx <= 0.5f) return
        neckShadow.strokeWidth = widthPx
        neckFace.strokeWidth = widthPx
        val dx = (1.5f * d)
        canvas.drawLine(a.x + dx, a.y + dx, b.x + dx, b.y + dx, neckShadow)
        canvas.drawLine(a.x, a.y, b.x, b.y, neckFace)
    }

    private companion object {
        const val EXPAND_MS = 300L
        const val COLLAPSE_MS = 220L
        const val GLYPH_SEARCH = "⌕"
    }
}
