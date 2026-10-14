package com.workspace.proot

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * 快捷栏/wheel 共用底板（bandHost）：方向无关的竖向滑动检测。
 * 任意方向竖向滑动（上/下均可、可一路滑出带外）→ 触发 onVerticalSwipe 做 A/B 循环切换；
 * 轻点与横向滑动透传给子控件（按键点击 / wheel 横滚 / 灰色手势）。
 *
 * ## 5.9.55：本容器是**独立的横滑触控区**，不参与页签横滑
 *
 * 5.9.53 把切页手势从「只挂在 terminalView 上」换成「覆盖整个 terminalArea 的 ViewPager2」，
 * 手势范围因此扩大到了整个终端页 —— 本容器和它内部那两个**横向滚动的 RecyclerView**
 * （`wheelRecycler` / `upperWheelRecycler`）就被圈进了页签拖动的范围里，
 * 与 ViewPager2 抢同一个横向手势。真机表现是 wheel「又能触发、又不利索」。
 *
 * 所以在这里按下即声明：**这次触摸归本 band，祖先不许拦截。**
 * 效果完全等同于 5.9.53 之前——底部 band 从不触发切页，wheel 横滚 / 按键点击 /
 * A/B 竖向切换全按原样工作。
 *
 * ## 为什么必须绑在**视图**上，任何时候不许改成坐标判断
 *
 * 本 band 的位置会不停变：双击弹/收输入法 → `adjustResize` → 整个窗口重排 →
 * band 上移或被压缩；A/B 切换时内部三个子视图还在 `translationY` 上做动画。
 *
 * 但触摸派发是**按视图树**做的，从不由坐标决定命中谁。所以只要落点在本容器内，
 * `ACTION_DOWN` 必定先到这里，无论 band 此刻在屏幕底部、被键盘顶上去一半、
 * 还是正处在切换动画中途 —— 本规则都成立，**移动多少次都不会错位**。
 *
 * ⚠ 反过来：若图省事写成「y > 某阈值就 disallow」，键盘一弹阈值就失效，
 * 这正是本条注释要防的事。任何按坐标/阈值划分边界的改法都是回归。
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

    /** 只读触摸观察：不拦截、不改分发，供 BAND 级灰区长按守卫使用（落在 recyclers 之外的空白带触摸）。 */
    var onDispatchTouch: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        onDispatchTouch?.invoke(ev)
        // 5.9.55：按下即把这次触摸的所有权锁定在本 band，祖先（含 ViewPager2）一律不许拦截。
        //
        // 时序保证：父容器 dispatchTouchEvent 处理 ACTION_DOWN 时会先
        // `resetTouchState()` 清掉 disallow-intercept 标志，而本方法是在那之后、
        // 事件派发给子视图的过程中被调用的 —— 所以这一句一定来得及生效，
        // 且下一次新手势开始时标志会被自动重置，不会粘住。
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(ev)
    }

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

