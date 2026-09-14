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
 * M3 分段按钮组样式：一个圆角描边容器内，段间 1dp 分隔线，选中段填充、其余透明。
 * 只负责外观，不改变任何布局结构、点击监听与业务逻辑。
 */
object SegmentStyle {
    const val RADIUS_DP = 20f
    private const val STROKE_DP = 1f
    private const val TAG = "seg_divider"
    private val rippleColor = ColorStateList.valueOf(0x1FFFFFFF.toInt())

    /** 选中段的配色方案（品牌绿语义色，可传 secondaryContainer/errorContainer 系）。 */
    data class Fill(val color: Int, val onColor: Int)

/** 段端点圆角开关。 */
    data class Round(val left: Boolean, val right: Boolean)

    /** 段外框尺寸：外端圆角半径与段间描边内缩量。 */
    data class Shape(val radius: Float, val inset: Int)

    /** 把一行按钮渲染成分段组：fills 顺序对应各段（null = 未选中/透明）。 */
    fun applyRow(row: LinearLayout, fills: List<Fill?>, outline: Int, onSurface: Int) {
        val d = row.resources.displayMetrics.density
        val radius = RADIUS_DP * d
        val stroke = (STROKE_DP * d).toInt()

        row.background = GradientDrawable().apply {
            cornerRadius = radius
            setStroke(stroke, outline)
            setColor(Color.TRANSPARENT)
        }
        ensureDividers(row, stroke, outline)
        relayoutMargins(row)

        var segment = 0
        val n = row.childCount
        for (i in 0 until n) {
            val child = row.getChildAt(i)
            if (child.tag == TAG) continue
            val fill = fills.getOrNull(segment)
            val round = Round(segment == 0, fills.isNotEmpty() && segment == fills.lastIndex)
            styleSegment(child as Button, fill, Shape(radius, stroke), onSurface, round)
            segment++
        }
    }

    private fun ensureDividers(row: LinearLayout, stroke: Int, outline: Int) {
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
                    setBackgroundColor(outline)
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
