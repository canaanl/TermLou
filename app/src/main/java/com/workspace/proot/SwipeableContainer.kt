package com.workspace.proot

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * 快捷栏/wheel 共用底板（bandHost）：方向无关的竖向滑动检测。
 * 任意方向竖向滑动（上/下均可、可一路滑出带外）→ 触发 onVerticalSwipe 做 A/B 循环切换；
 * 轻点与横向滑动透传给子控件（按键点击 / wheel 横滚 / 灰色手势）。
 */
class SwipeableContainer(context: Context) : FrameLayout(context) {

private var startX = 0f
    private var startY = 0f
    private var intercepted = false
    private var downInBand = false
    private var directSwipe = false
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private val verticalThreshold by lazy { (28 * resources.displayMetrics.density) }

    /** downward=true 表示手指下滑（内容跟手往下推开）。 */
    var onVerticalSwipe: ((Boolean) -> Unit)? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                downInBand = true
                intercepted = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!intercepted && downInBand && ev.pointerCount == 1) {
                    val dy = Math.abs(ev.y - startY)
                    val dx = Math.abs(ev.x - startX)
                    if (dy > verticalThreshold && dy > dx) {
                        intercepted = true
                        return true
                    }
                }
            }
        }
        return false
    }

override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                intercepted = false
                directSwipe = false
            }
            MotionEvent.ACTION_MOVE -> {
                // 直达本容器的触摸（按下点落在空白区，子控件全部拒绝）：
                // onInterceptTouchEvent 只在子控件为目标时才会被查询，此路径必须在本类自行判定竖滑。
                if (!intercepted) {
                    val dy = Math.abs(ev.y - startY)
                    val dx = Math.abs(ev.x - startX)
                    if (dy > verticalThreshold && dy > dx) directSwipe = true
                }
            }
            MotionEvent.ACTION_UP -> {
                val triggered = intercepted
                intercepted = false
                if (directSwipe || triggered) {
                    val dy = ev.y - startY
                    if (Math.abs(dy) >= touchSlop) onVerticalSwipe?.invoke(dy > 0)
                }
                directSwipe = false
            }
            MotionEvent.ACTION_CANCEL -> {
                intercepted = false
                directSwipe = false
            }
        }
        return true
    }
}

