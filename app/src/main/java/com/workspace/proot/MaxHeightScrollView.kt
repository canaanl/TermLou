package com.workspace.proot

import android.content.Context
import android.widget.ScrollView

/**
 * 带最大高度的 ScrollView：内容不超过上限时按内容高（WRAP_CONTENT 表现），
 * 超过上限时夹到 maxHeight 并竖向滚动。只用于约束编辑页标签区高度，
 * 避免标签过多时挤掉正文。maxHeight <= 0 表示不限。
 */
class MaxHeightScrollView(context: Context) : ScrollView(context) {

    var maxHeight: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (maxHeight > 0 && measuredHeight > maxHeight) {
            setMeasuredDimension(measuredWidth, maxHeight)
        }
    }
}
