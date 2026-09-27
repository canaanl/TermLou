package com.workspace.proot

import android.content.Context
import android.view.MotionEvent
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 纵向跟手容器：竖向主导且超过 slop 时拦截（children 收 CANCEL，不会误触点按），
 * 拖动过程实时把位移上报 `onDrag(dy)`（下为正），抬手/取消报 `onDragEnd(dy, cancelled)`，
 * 由宿主决定补完换页还是回弹。
 * 与 TabSwipeLayout 同机制：触发点放 dispatchTouchEvent、拦截放 onInterceptTouchEvent——
 * OnTouchListener 挂 ViewGroup 上会被消费触摸的 children 屏蔽，容器级手势必须走 intercept。
 *
 * 另外必须 isClickable：触摸落在不可点区域（灰字格、周表头、网格上下的空白）时
 * 若没人接受 DOWN，父链就不会建 touch target，后续 MOVE/UP 根本送不进来，
 * 表现就是「只有可点的本月格子才能滑」。容器自己接下 DOWN 即可全区域生效。
 */
class MonthSwipeLayout(
    context: Context,
    private val onDrag: (Float) -> Unit = {},
    private val onDragEnd: (Float, Boolean) -> Unit = { _, _ -> }
) : LinearLayout(context) {

    init {
        isClickable = true
    }

    private var startX = 0f
    private var startY = 0f
    /** 跟手/拦截起点：超过它且竖向占优才认定为拖动（20dp 内的点按照常命中格子）。 */
    private val slop by lazy { (20 * resources.displayMetrics.density).toInt() }
    /** 一旦起手就锁住，后续横向摆动不会让位移上报中断（网格冻结在半路）。 */
    private var dragging = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - startY
                val dx = abs(ev.x - startX)
                if (!dragging && abs(dy) > slop && abs(dy) > dx) dragging = true
                if (dragging) onDrag(dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDragEnd(ev.y - startY, ev.actionMasked == MotionEvent.ACTION_CANCEL)
                dragging = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            val dy = ev.y - startY
            val dx = abs(ev.x - startX)
            if (abs(dy) > slop && abs(dy) > dx) return true
        }
        return super.onInterceptTouchEvent(ev)
    }
}
