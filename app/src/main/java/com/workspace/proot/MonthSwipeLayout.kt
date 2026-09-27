package com.workspace.proot

import android.content.Context
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * 纵向滑动翻页容器：竖向主导且超过阈值时拦截（children 收 CANCEL，不会误触点按），
 * 抬手触发回调（上滑=下一页、下滑=上一页）。
 * 与 TabSwipeLayout 同机制：触发点放 dispatchTouchEvent、拦截放 onInterceptTouchEvent——
 * OnTouchListener 挂 ViewGroup 上会被消费触摸的 children 屏蔽，容器级手势必须走 intercept。
 *
 * 另外必须 isClickable：触摸落在不可点区域（灰字格、周表头、网格上下的空白）时
 * 若没人接受 DOWN，父链就不会建 touch target，后续 MOVE/UP 根本送不进来，
 * 表现就是「只有可点的本月格子才能滑」。容器自己接下 DOWN 即可全区域生效。
 */
class MonthSwipeLayout(
    context: Context,
    private val onSwipeUp: () -> Unit = {},
    private val onSwipeDown: () -> Unit = {}
) : LinearLayout(context) {

    init {
        isClickable = true
    }

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
                val dy = ev.y - startY
                val dx = kotlin.math.abs(ev.x - startX)
                if (kotlin.math.abs(dy) > threshold && kotlin.math.abs(dy) > dx) {
                    if (dy < 0) onSwipeUp() else onSwipeDown()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - startY
                val dx = kotlin.math.abs(ev.x - startX)
                if (kotlin.math.abs(dy) > threshold && kotlin.math.abs(dy) > dx) return true
            }
        }
        return super.onInterceptTouchEvent(ev)
    }
}
