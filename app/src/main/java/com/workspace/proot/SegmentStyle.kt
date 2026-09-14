package com.workspace.proot

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

/**
 * M3 分段按钮组样式：圆角描边容器（或无边框全宽条）内，段间 1dp 分隔线，选中段填充、其余透明。
 * 只负责外观，不改变任何布局结构、点击监听与业务逻辑。
 */
object SegmentStyle {
const val RADIUS_DP = 20f
    private const val STROKE_DP = 1f
    private const val TAG = "seg_divider"
    private const val DIVIDER_ALPHA = 0x66
    private const val RGB_MASK = 0x00FFFFFF
    private const val ALPHA_SHIFT = 24
    private val rippleColor = ColorStateList.valueOf(0x1FFFFFFF.toInt())

    /** 选中段的配色方案（品牌绿语义色，可传 secondaryContainer/errorContainer 系）。 */
    data class Fill(val color: Int, val onColor: Int)

/** 段端点圆角开关。 */
    data class Round(val left: Boolean, val right: Boolean)

    /** 段外框尺寸：外端圆角半径与段间描边内缩量。 */
    data class Shape(val radius: Float, val inset: Int)

    /** 行容器形态：圆角半径（dp）与是否保留外描边框。无边框=全宽分段条。 */
    data class Bar(val radiusDp: Float = RADIUS_DP, val bordered: Boolean = true)

    /** 行间横向分隔线（终端两键行之间、网络两行之间），无大边距；颜色同段间分隔线。 */
    fun hDivider(context: android.content.Context, onSurface: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (STROKE_DP * resources.displayMetrics.density).toInt()
        )
        setBackgroundColor(dividerColor(onSurface))
    }

    /** 把一行按钮渲染成分段组：fills 顺序对应各段（null = 未选中/透明）。 */
    fun applyRow(
        row: LinearLayout,
        fills: List<Fill?>,
        outline: Int,
        onSurface: Int,
        bar: Bar = Bar()
    ) {
        val d = row.resources.displayMetrics.density
        val radius = bar.radiusDp * d
        val stroke = (STROKE_DP * d).toInt()

        // 边框画在 foreground 浮层（盖在全幅填充之上，像素级对齐，无错位缝）；
        // 无边框=透明，段间分隔线照常保留。段填充一律全幅顶满（inset 0）。
        row.background = null
        row.foreground = if (bar.bordered) {
            GradientDrawable().apply {
                cornerRadius = radius
                setStroke(stroke, outline)
                setColor(Color.TRANSPARENT)
            }
        } else {
            null
        }
        ensureDividers(row, stroke, dividerColor(onSurface))
        relayoutMargins(row)

        var segment = 0
        val n = row.childCount
        for (i in 0 until n) {
            val child = row.getChildAt(i)
            if (child.tag == TAG) continue
            val fill = fills.getOrNull(segment)
            val round = Round(segment == 0, fills.isNotEmpty() && segment == fills.lastIndex)
            styleSegment(child as Button, fill, Shape(radius, 0), onSurface, round)
            segment++
        }
    }

    /** 分隔线用 onSurface 40% 透明：outline 在深底上对比度不足，实测不可见；两档主题自适应。 */
    private fun dividerColor(onSurface: Int): Int =
        (onSurface and RGB_MASK) or (DIVIDER_ALPHA shl ALPHA_SHIFT)

private fun ensureDividers(row: LinearLayout, stroke: Int, divider: Int) {
        var i = 0
        while (i < row.childCount) {
            if (row.getChildAt(i).tag == TAG) row.removeViewAt(i) else i++
        }
        val segments = row.childCount
        if (segments <= 1) return
        var insertAt = 1
        while (insertAt < row.childCount) {
            row.addView(
                View(row.context).apply {
                    tag = TAG
                    setBackgroundColor(divider)
                },
                insertAt,
                LinearLayout.LayoutParams(stroke, LinearLayout.LayoutParams.MATCH_PARENT)
            )
            insertAt += 2
        }
    }

    /** 段间需要紧贴：清掉既有的外距（仅此一行内生效，不改所有权重/宽度）。 */
    private fun relayoutMargins(row: LinearLayout) {
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i)
            if (v.tag != TAG && v.layoutParams is LinearLayout.LayoutParams) {
                val lp = v.layoutParams as LinearLayout.LayoutParams
                lp.setMargins(0, lp.topMargin, 0, lp.bottomMargin)
            }
        }
    }

private fun styleSegment(
        button: Button,
        fill: Fill?,
        shape: Shape,
        onSurface: Int,
        round: Round
    ) {
        val radius = shape.radius
        val inset = shape.inset
        val lr = if (round.left) radius else 0f
        val rr = if (round.right) radius else 0f
        val content = GradientDrawable().apply {
            setColor(fill?.color ?: Color.TRANSPARENT)
            cornerRadii = floatArrayOf(lr, lr, rr, rr, rr, rr, lr, lr)
        }
        val insetLayer = LayerDrawable(arrayOf(content)).apply {
            setLayerInset(0, inset, inset, inset, inset)
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radius
        }
        button.background = RippleDrawable(rippleColor, insetLayer, mask)
        button.setTextColor(fill?.onColor ?: onSurface)
        button.isAllCaps = false
    }
}
