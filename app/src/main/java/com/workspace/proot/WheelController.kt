package com.workspace.proot

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface WheelDataSource {
    fun loadShortcutList(): List<ShortcutItem>
    fun emptyCountFor(list: List<ShortcutItem>): Int
    fun recommendedStartPos(force: Boolean): Int
    fun wheelCycleSize(): Int
    fun groupStartPos(
        members: List<ShortcutItem.Command>,
        emptyCount: Int,
        base: Int,
        cycleSize: Int
    ): Int
    fun execute(item: ShortcutItem)
}

/** 长按灰色区域进入 wheel 设置的时间阈值：满 1s 开始扩散辉光，松手进设置。 */
private const val GRAY_LONG_PRESS_MS = 1000L

class WheelController(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val dataSource: WheelDataSource,
    private val wheelPanel: FrameLayout,
    private val wheelRecycler: RecyclerView,
    private val upperWheelPanel: FrameLayout,
    private val upperWheelRecycler: RecyclerView,
    private var wheelCardH: Int,
    private val onStatusRestore: () -> Unit = {}
) {
    var onWheelPhase: ((Boolean, Boolean) -> Unit)? = null
    var onGrayTap: (() -> Unit)? = null

    /** 长按灰色进入设置。rawX/rawY 为屏幕坐标（按住点），供辉光特效定位。 */
    var onGrayLongPress: ((View, Float, Float) -> Unit)? = null

    /** 蓄力完成（按住满 1s）：从按住点开始扩散辉光，此时手指仍按着。 */
    var onGrayHold: ((View, Float, Float) -> Unit)? = null

    /** 蓄力被取消（移动/抢断/切走）：复位辉光叠加层。 */
    var onGrayCancel: (() -> Unit)? = null

    private val snapHelper = SkipEmptySnapHelper().apply { attachToRecyclerView(wheelRecycler) }
    private val upperSnapHelper = SkipEmptySnapHelper().apply { attachToRecyclerView(upperWheelRecycler) }

    private val grayGuard = GrayGuard(
        wheelRecycler,
        onGrayTap = { onGrayTap?.invoke() },
        onGrayHold = { v, x, y -> onGrayHold?.invoke(v, x, y) },
        onGrayLongPress = { v, x, y -> onGrayLongPress?.invoke(v, x, y) },
        onGrayCancel = { onGrayCancel?.invoke() }
    )
    private val upperGrayGuard = GrayGuard(
        upperWheelRecycler,
        onGrayTap = { onGrayTap?.invoke() },
        onGrayHold = { v, x, y -> onGrayHold?.invoke(v, x, y) },
        onGrayLongPress = { v, x, y -> onGrayLongPress?.invoke(v, x, y) },
        onGrayCancel = { onGrayCancel?.invoke() }
    )
    private val bandGuard = BandGrayGuard(
        wheelPanel,
        onGrayHold = { v, x, y -> onGrayHold?.invoke(v, x, y) },
        onGrayLongPress = { v, x, y -> onGrayLongPress?.invoke(v, x, y) },
        onGrayCancel = { onGrayCancel?.invoke() },
        isInRecycler = { rawX, rawY -> inVisibleRecycler(rawX, rawY) },
        isWheelVisible = { wheelPanel.visibility == View.VISIBLE }
    )

    private var lastDetectedTui = ""
    private var wheelStartPos = Int.MAX_VALUE / 2
    private var upperWheelAdapter: WheelAdapter? = null
    private var currentUpperGroup: String? = null
    private var pendingWheelRealign = false
    private var pendingUpperRealign = false
    private var pendingUpperGroup: ShortcutItem.Group? = null
    private var lastCenteredItem: ShortcutItem? = null
    private var suppressUpperEntryAnim = false

    /** BAND 推送切换期间禁用上层 wheel 独立的入场/退场动画，让它随带一起推入，避免上轮落后出现。 */
    fun setSuppressUpperEntryAnim(suppress: Boolean) {
        suppressUpperEntryAnim = suppress
    }

    init {
        wheelRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateWheelGlow()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (pendingWheelRealign) alignWheelToLanding()
                    settleUpperWheel()
                }
            }
        })
        upperWheelRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateUpperWheelGlow()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && pendingUpperRealign) {
                    pendingUpperGroup?.let { recenterUpperToBest(it) }
                }
            }
        })
        installGrayGestures(wheelRecycler)
        installGrayGestures(upperWheelRecycler)
    }

    /**
     * 灰色（非聚焦卡片/空白槽）手势。通过 NonFlingRecyclerView.dispatchTouchEvent
     * 只读观察全部触摸（不论被子控件消费与否），因此轻点/长按任意灰色区域
     * （含非居中命令卡片）都能可靠命中，且不拦截事件、不破坏居中卡片的点按执行与车轮横滚。
     */
    private fun installGrayGestures(rv: RecyclerView) {
        val guard = if (rv === wheelRecycler) grayGuard else upperGrayGuard
        (rv as? NonFlingRecyclerView)?.onDispatchTouch = { ev -> guard.onEvent(ev) }
    }

    /** 取消带内容器被父级抢占后的悬空长按计时（竖向滑动切走时防止误触设置）。 */
    private fun cancelBandGestures() {
        grayGuard.cancelPending()
        upperGrayGuard.cancelPending()
        bandGuard.cancelPending()
    }

    /** BAND 空白区触摸（由 SwipeableContainer.dispatchTouchEvent 观察口送入）：白框以外的区域长按进设置。 */
    fun onBandTouch(ev: MotionEvent) {
        bandGuard.onEvent(ev)
    }

    private val tmpLoc = IntArray(2)

    private fun inVisibleRecycler(rawX: Float, rawY: Float): Boolean {
        val inLower = wheelPanel.visibility == View.VISIBLE && containsPoint(wheelRecycler, rawX, rawY)
        val inUpper = upperWheelPanel.visibility == View.VISIBLE && containsPoint(upperWheelRecycler, rawX, rawY)
        return inLower || inUpper
    }

    private fun containsPoint(v: View, rawX: Float, rawY: Float): Boolean {
        v.getLocationOnScreen(tmpLoc)
        return rawX >= tmpLoc[0] && rawX < tmpLoc[0] + v.width &&
            rawY >= tmpLoc[1] && rawY < tmpLoc[1] + v.height
    }

    private class GrayGuard(
        private val rv: RecyclerView,
        private val onGrayTap: () -> Unit,
        private val onGrayHold: (View, Float, Float) -> Unit,
        private val onGrayLongPress: (View, Float, Float) -> Unit,
        private val onGrayCancel: () -> Unit
    ) {

        private val slop by lazy { ViewConfiguration.get(rv.context).scaledTouchSlop }
        private var downX = 0f
        private var downY = 0f
        private var downRawX = 0f
        private var downRawY = 0f
        private var armed = false
        private var longFired = false
        private var glowActive = false
        private var pressedView: View? = null
        private val armTask = Runnable {
            glowActive = true
            longFired = true
            pressedView?.let { onGrayHold(it, downRawX, downRawY) }
        }

        fun cancelPending() {
            if (armed || glowActive) cancelAll() else resetFired()
        }

        private fun resetFired() {
            rv.removeCallbacks(armTask)
            armed = false
            longFired = false
            glowActive = false
            pressedView = null
        }

        private fun cancelAll() {
            val wasGlowing = glowActive
            resetFired()
            if (wasGlowing) onGrayCancel()
        }

        fun onEvent(ev: MotionEvent) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    pressedView = rv.findChildViewUnder(ev.x, ev.y)
                    armed = isGrayZone(pressedView)
                    longFired = false
                    glowActive = false
                    if (armed) rv.postDelayed(armTask, GRAY_LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!armed) return
                    if (Math.abs(ev.x - downX) > slop || Math.abs(ev.y - downY) > slop) {
                        cancelAll()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (glowActive) {
                        val v = pressedView
                        resetFired()
                        v?.let { onGrayLongPress(it, downRawX, downRawY) }
                    } else {
                        val wasArmed = armed
                        resetFired()
                        if (wasArmed) onGrayTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> cancelAll()
            }
        }

        private fun isGrayZone(child: View?): Boolean {
            val btn = child as? Button
            if (btn == null || btn.text.isNullOrEmpty()) return true
            val center = rv.width / 2f
            val cCenter = btn.left + btn.width / 2f
            return Math.abs(cCenter - center) >= btn.width / 2f
        }
    }

    /**
     * BAND 级灰区守卫：处理落在两个可见 recycler 之外、白框之外的空白带触摸
     * （单层 wheel 时上半区空位；双层时带内无空位，天然不触发）。
     * 与 GrayGuard 同语义（1s 蓄力辉光、松手进设置、滑动/CANCEL 取消），唯独短点无操作，
     * 保持该区域现有点击无行为不变。落入 recycler 的触摸一律忽略，归各 recycler 守卫处理，
     * 构造上不可能双重触发。
     */
    private class BandGrayGuard(
        private val host: View,
        private val onGrayHold: (View, Float, Float) -> Unit,
        private val onGrayLongPress: (View, Float, Float) -> Unit,
        private val onGrayCancel: () -> Unit,
        private val isInRecycler: (rawX: Float, rawY: Float) -> Boolean,
        private val isWheelVisible: () -> Boolean
    ) {

        private val slop by lazy { ViewConfiguration.get(host.context).scaledTouchSlop }
        private var downRawX = 0f
        private var downRawY = 0f
        private var armed = false
        private var glowActive = false
        private val armTask = Runnable {
            glowActive = true
            onGrayHold(host, downRawX, downRawY)
        }

        fun cancelPending() {
            if (armed || glowActive) cancelAll() else resetFired()
        }

        private fun resetFired() {
            host.removeCallbacks(armTask)
            armed = false
            glowActive = false
        }

        private fun cancelAll() {
            val wasGlowing = glowActive
            resetFired()
            if (wasGlowing) onGrayCancel()
        }

        fun onEvent(ev: MotionEvent) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    glowActive = false
                    armed = isWheelVisible() && !isInRecycler(ev.rawX, ev.rawY)
                    if (armed) host.postDelayed(armTask, GRAY_LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!armed) return
                    if (Math.abs(ev.rawX - downRawX) > slop || Math.abs(ev.rawY - downRawY) > slop) {
                        cancelAll()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (glowActive) {
                        resetFired()
                        onGrayLongPress(host, downRawX, downRawY)
                    } else {
                        resetFired()
                    }
                }
                MotionEvent.ACTION_CANCEL -> cancelAll()
            }
        }
    }

    fun updateCardHeight(h: Int) {
        wheelCardH = h
    }

    fun toggle(downward: Boolean) {
        cancelBandGestures()
        if (wheelPanel.visibility == View.VISIBLE) hide(downward) else show(downward)
    }

    fun hide(downward: Boolean = true) {
        if (wheelPanel.visibility != View.VISIBLE) return
        cancelBandGestures()
        onWheelPhase?.invoke(false, downward)
        pendingWheelRealign = false
        lastCenteredItem = null
        hideUpperWheel(false)
    }

    fun onResume() {
        if (wheelPanel.visibility == View.VISIBLE) refreshAndApply(force = true, smooth = true, alignAlways = false)
    }

    fun refreshAll() {
        currentUpperGroup = null
        pendingWheelRealign = false
        pendingUpperRealign = false
        pendingUpperGroup = null
        lastCenteredItem = null
        upperWheelAdapter = null
        upperWheelRecycler.adapter = null
        upperWheelPanel.animate().cancel()
        upperWheelPanel.visibility = View.GONE
        upperWheelPanel.translationY = 0f
        upperWheelPanel.alpha = 1f

        val list = dataSource.loadShortcutList()
        if (list.isEmpty()) {
            wheelStartPos = Int.MAX_VALUE / 2
            return
        }
        val emptyCount = dataSource.emptyCountFor(list)
        wheelRecycler.adapter = WheelAdapter(
            list, emptyCount,
            onHighlightClick = { item -> dataSource.execute(item) }
        )
        snapHelper.isEmptyCheck = { pos ->
            (wheelRecycler.adapter as? WheelAdapter)?.isEmpty(pos) ?: false
        }
        wheelStartPos = dataSource.recommendedStartPos(false)
        wheelRecycler.scrollToPosition(wheelStartPos)
    }

    private fun show(downward: Boolean) {
        cancelBandGestures()
        onWheelPhase?.invoke(true, downward)
        refreshAndApply(force = true, smooth = false, alignAlways = true)
    }

    private fun refreshAndApply(force: Boolean, smooth: Boolean, alignAlways: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (force) TuiStateDetector.refresh() else TuiStateDetector.getCurrentState()
            }
            applyTuiState(smooth, alignAlways)
        }
    }

    private fun applyTuiState(smooth: Boolean, alignAlways: Boolean) {
        if (wheelPanel.visibility != View.VISIBLE) return
        val state = TuiStateDetector.getCurrentState()
        if (!alignAlways && state == lastDetectedTui) return
        lastDetectedTui = state
        onStatusRestore()
        wheelStartPos = dataSource.recommendedStartPos(false)
        if (smooth) {
            alignWheelToLanding()
        } else {
            jumpWheelToLanding()
        }
    }

    private fun jumpWheelToLanding() {
        wheelRecycler.post {
            val lm = wheelRecycler.layoutManager as? LinearLayoutManager ?: return@post
            lm.scrollToPositionWithOffset(wheelStartPos, context.resources.displayMetrics.widthPixels / 3)
            updateWheelGlow()
        }
    }

    private fun alignWheelToLanding() {
        if (wheelRecycler.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            pendingWheelRealign = true
            return
        }
        pendingWheelRealign = false
        val cycle = dataSource.wheelCycleSize()
        if (cycle <= 0) return
        val targetSlot = wheelStartPos % cycle
        val curSlot = centeredSlot(wheelRecycler, cycle)
        if (curSlot == null) {
            jumpWheelToLanding()
            return
        }
        val delta = slotDelta(curSlot, targetSlot, cycle)
        if (delta == 0) return
        wheelRecycler.smoothScrollBy(delta * slotWidthPx, 0)
    }

    private fun centeredChild(rv: RecyclerView): View? {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return null
        val center = rv.width / 2f
        for (i in 0 until lm.childCount) {
            val child = lm.getChildAt(i) ?: continue
            val childCenter = child.left + child.width / 2f
            if (Math.abs(childCenter - center) < child.width / 2f) return child
        }
        return null
    }

    private fun centeredSlot(rv: RecyclerView, cycle: Int): Int? {
        val child = centeredChild(rv) ?: return null
        val pos = rv.getChildLayoutPosition(child)
        return if (pos == RecyclerView.NO_POSITION) null else pos % cycle
    }

    private fun slotDelta(cur: Int, target: Int, cycle: Int): Int {
        var d = target - cur
        if (d > cycle / 2) d -= cycle
        if (d < -cycle / 2) d += cycle
        return d
    }

    private val slotWidthPx: Int get() = context.resources.displayMetrics.widthPixels / 3 + 6

    private fun updateWheelGlow() {
        applyGlow(wheelRecycler)
        syncUpperWheel()
    }

    private fun updateUpperWheelGlow() {
        applyGlow(upperWheelRecycler)
    }

    private fun applyGlow(rv: RecyclerView) {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val center = rv.width / 2f
        for (i in 0 until lm.childCount) {
            val btn = lm.getChildAt(i) as? Button ?: continue
            if (btn.text.isEmpty()) continue
            val childCenter = btn.left + btn.width / 2f
            val dist = Math.abs(childCenter - center) / (btn.width / 2f)
            val scale = 1f - 0.14f * dist.coerceIn(0f, 1f)
            if (Math.abs(btn.scaleX - scale) > 0.01f) {
                btn.scaleX = scale
                btn.scaleY = scale
            }
        }
    }

    private fun syncUpperWheel() {
        if (wheelPanel.visibility != View.VISIBLE) {
            lastCenteredItem = null
            hideUpperWheel(false)
            return
        }
        val child = centeredChild(wheelRecycler) ?: return
        val item = child.tag as? ShortcutItem ?: return
        if (item === lastCenteredItem) return
        lastCenteredItem = item
        val group = item as? ShortcutItem.Group
        if (group == null || group.members.isEmpty()) {
            hideUpperWheel(true)
        } else {
            showUpperWheel(group)
        }
    }

    private fun settleUpperWheel() {
        if (wheelPanel.visibility != View.VISIBLE) return
        val child = centeredChild(wheelRecycler) ?: return
        val group = child.tag as? ShortcutItem.Group ?: return
        if (group.members.isEmpty()) return
        recenterUpperToBest(group)
    }

    private fun showUpperWheel(group: ShortcutItem.Group) {
        if (currentUpperGroup == group.name && upperWheelPanel.visibility == View.VISIBLE) {
            recenterUpperToBest(group)
            return
        }
        pendingUpperRealign = false
        pendingUpperGroup = null
        currentUpperGroup = group.name
        val members = group.members.toList()
        upperWheelAdapter = WheelAdapter(
            items = members,
            emptyCount = if (members.size <= 2) 1 else 0,
            onHighlightClick = { item -> dataSource.execute(item) }
        )
        upperWheelRecycler.adapter = upperWheelAdapter
        upperSnapHelper.isEmptyCheck = { pos ->
            (upperWheelRecycler.adapter as? WheelAdapter)?.isEmpty(pos) ?: false
        }
        val cycleSize = members.size + (if (members.size <= 2) 1 else 0)
        val base = Int.MAX_VALUE / 2
        val emptyCount = if (members.size <= 2) 1 else 0
        val startPos = dataSource.groupStartPos(
            members, emptyCount, base, cycleSize
        )
        (upperWheelRecycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
            startPos, context.resources.displayMetrics.widthPixels / 3
        )
        upperWheelPanel.animate().cancel()
        upperWheelPanel.visibility = View.VISIBLE
        if (suppressUpperEntryAnim) {
            // BAND 共享滑移动画正在逐帧驱动 upper（switchBand 的 ValueAnimator 把
            // upper.translationY 写到与 wheelPanel 相同的位置曲线），此处只置可见并填充内容，
            // 绝不写入 translationY/alpha，否则会把上层瞬时跳到终点造成与下层不同步。
        } else {
            upperWheelPanel.translationY = wheelCardH.toFloat()
            upperWheelPanel.alpha = 0f
            upperWheelPanel.animate().translationY(0f).alpha(1f).setDuration(160)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        upperWheelRecycler.post { updateUpperWheelGlow() }
    }

    private fun recenterUpperToBest(group: ShortcutItem.Group) {
        if (upperWheelRecycler.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            pendingUpperRealign = true
            pendingUpperGroup = group
            return
        }
        pendingUpperRealign = false
        pendingUpperGroup = null
        val members = group.members
        val emptyCount = if (members.size <= 2) 1 else 0
        val cycle = members.size + emptyCount
        if (cycle <= 0) return
        val base = Int.MAX_VALUE / 2
        val targetPos = dataSource.groupStartPos(
            members, emptyCount, base, cycle
        )
        val targetSlot = targetPos % cycle
        val curSlot = centeredSlot(upperWheelRecycler, cycle)
        if (curSlot == null) {
            (upperWheelRecycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                targetPos, context.resources.displayMetrics.widthPixels / 3
            )
            upperWheelRecycler.post { updateUpperWheelGlow() }
            return
        }
        val delta = slotDelta(curSlot, targetSlot, cycle)
        if (delta == 0) return
        upperWheelRecycler.smoothScrollBy(delta * slotWidthPx, 0)
    }

    private fun hideUpperWheel(animate: Boolean) {
        pendingUpperRealign = false
        pendingUpperGroup = null
        if (upperWheelPanel.visibility == View.GONE) {
            currentUpperGroup = null
            return
        }
        if (animate && !suppressUpperEntryAnim) {
            upperWheelPanel.animate().cancel()
            upperWheelPanel.animate().translationY(wheelCardH.toFloat()).alpha(0f).setDuration(150)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    upperWheelPanel.visibility = View.GONE
                    upperWheelPanel.translationY = 0f
                    upperWheelPanel.alpha = 1f
                }.start()
        } else {
            upperWheelPanel.visibility = View.GONE
            upperWheelPanel.translationY = 0f
            upperWheelPanel.alpha = 1f
        }
        currentUpperGroup = null
    }
}
