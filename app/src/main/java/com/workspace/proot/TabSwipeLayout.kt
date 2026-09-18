package com.workspace.proot

import android.content.Context
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * 横滑切换容器（纵列版 TabSwipeScrollView）：只在横向主导且超过阈值时
 * 拦截手势并触发回调；点按、竖滑原样放行给 children。
 * 注意：OnTouchListener 挂 ViewGroup 上会被消费触摸的 children 屏蔽，
 * 容器级手势必须走 onInterceptTouchEvent。
 */
class TabSwipeLayout(
    context: Context,
    private val onSwipeRight: () -> Unit = {},
    private val onSwipeLeft: () -> Unit = {}
) : LinearLayout(context) {
    private var startX = 0f
    private var startY = 0f
    private val threshold by lazy { (50 * resources.displayMetrics.density).toInt() }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - startX
                val dy = kotlin.math.abs(ev.y - startY)
                if (kotlin.math.abs(dx) > threshold && kotlin.math.abs(dx) > dy) {
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
                val dy = kotlin.math.abs(ev.y - startY)
                if (kotlin.math.abs(dx) > threshold && kotlin.math.abs(dx) > dy) {
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }
}
