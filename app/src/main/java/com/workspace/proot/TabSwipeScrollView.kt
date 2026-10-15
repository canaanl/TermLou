package com.workspace.proot

import android.content.Context
import android.view.MotionEvent
import android.widget.ScrollView

class TabSwipeScrollView(
    context: Context,
    private val onSwipeRight: () -> Unit = {},
    private val onSwipeLeft: () -> Unit = {}
) : ScrollView(context) {
    private var startX = 0f
    private var startY = 0f
    private val threshold by lazy { (50 * resources.displayMetrics.density).toInt() }

    init {
        // 5.9.64：关掉系统默认绘制的纵向滚动条 —— 设置页是自绘 UI，右侧细条显脏。
        isVerticalScrollBarEnabled = false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - startX
                val dy = Math.abs(ev.y - startY)
                if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy)) {
                    if (dx > 0) onSwipeRight() else onSwipeLeft()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - startX
                val dy = Math.abs(ev.y - startY)
                if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy)) {
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }
}
