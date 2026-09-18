package com.workspace.proot

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * 标签流式换行容器：子 View 从左到右排，超出可用宽度自动换行，
 * 行高取该行最大值，行间 4dp；横向间距沿用子 View 的 marginEnd。
 * 只做布局，不含任何业务逻辑；子 View 宽度由父容器约束，不会横向溢出。
 */
class TagFlowLayout(context: Context) : ViewGroup(context) {

    private val lineGap = (LINE_GAP_DP * resources.displayMetrics.density).toInt()

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams): Boolean = p is MarginLayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val horizontalPadding = paddingLeft + paddingRight
        val available = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (widthSize - horizontalPadding).coerceAtLeast(0)
        }

        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = 0
        var maxLineWidth = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (lineWidth > 0 && lineWidth + childWidth > available) {
                totalHeight += lineHeight + lineGap
                maxLineWidth = maxOf(maxLineWidth, lineWidth)
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += childWidth
            lineHeight = maxOf(lineHeight, childHeight)
        }
        totalHeight += lineHeight
        maxLineWidth = maxOf(maxLineWidth, lineWidth)

        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(maxLineWidth + horizontalPadding, widthSize)
            else -> maxLineWidth + horizontalPadding
        }
        setMeasuredDimension(
            measuredWidth,
            resolveSize(totalHeight + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = (r - l) - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > paddingLeft && x - paddingLeft + childWidth > available) {
                x = paddingLeft
                y += lineHeight + lineGap
                lineHeight = 0
            }
            child.layout(
                x + lp.leftMargin,
                y + lp.topMargin,
                x + lp.leftMargin + child.measuredWidth,
                y + lp.topMargin + child.measuredHeight
            )
            x += childWidth
            lineHeight = maxOf(lineHeight, childHeight)
        }
    }

    private companion object {
        const val LINE_GAP_DP = 4
    }
}
