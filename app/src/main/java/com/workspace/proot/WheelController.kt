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

/** 长按灰色区域进入 wheel 设置的时间阈值。 */
private const val GRAY_LONG_PRESS_MS = 2000L

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
    var onWheelPhase: ((Boolean) -> Unit)? = null
    var onGrayTap: (() -> Unit)? = null

    /** 长按灰色进入设置。rawX/rawY 为屏幕坐标（抬手前触点），供辉光特效定位。 */
    var onGrayLongPress: ((View, Float, Float) -> Unit)? = null

    private val snapHelper = SkipEmptySnapHelper().apply { attachToRecyclerView(wheelRecycler) }
    private val upperSnapHelper = SkipEmptySnapHelper().apply { attachToRecyclerView(upperWheelRecycler) }

    private var lastDetectedTui = ""
    private var wheelStartPos = Int.MAX_VALUE / 2
    private var upperWheelAdapter: WheelAdapter? = null
    private var currentUpperGroup: String? = null
    private var pendingWheelRealign = false
    private var pendingUpperRealign = false
    private var pendingUpperGroup: ShortcutItem.Group? = null
    private var lastCenteredItem: ShortcutItem? = null

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

    /** 灰色（非聚焦卡片/空白槽）手势：轻点收回 wheel，长按 2s 触发辉光+设置。 */
    private fun installGrayGestures(rv: RecyclerView) {
        rv.setOnTouchListener(GrayGestures(
            rv = rv,
            onTap = { onGrayTap?.invoke() },
            onLongPress = { view, rawX, rawY -> onGrayLongPress?.invoke(view, rawX, rawY) }
        ))
    }

    private class GrayGestures(
        private val rv: RecyclerView,
        private val onTap: () -> Unit,
        private val onLongPress: (View, Float, Float) -> Unit
    ) : View.OnTouchListener {

        private val slop by lazy { ViewConfiguration.get(rv.context).scaledTouchSlop }
        private var downX = 0f
        private var downY = 0f
        private var downRawX = 0f
        private var downRawY = 0f
        private var moved = false
        private var armed = false
        private var pressedView: View? = null
        private val longPressTask = Runnable {
            if (armed) {
                armed = false
                pressedView?.let { onLongPress(it, downRawX, downRawY) }
            }
        }

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    moved = false
                    val child = rv.findChildViewUnder(ev.x, ev.y)
                    pressedView = child
                    armed = isGrayZone(child)
                    if (armed) v.removeCallbacks(longPressTask)
                    if (armed) v.postDelayed(longPressTask, GRAY_LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(ev.x - downX) > slop || Math.abs(ev.y - downY) > slop) {
                        moved = true
                        armed = false
                        v.removeCallbacks(longPressTask)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPressTask)
                    if (armed && !moved) {
                        armed = false
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressTask)
                    armed = false
                }
            }
            return false
        }

        private fun isGrayZone(child: View?): Boolean {
            val btn = child as? Button
            if (btn == null || btn.text.isNullOrEmpty()) return true
            val center = rv.width / 2f
            val cCenter = btn.left + btn.width / 2f
            return Math.abs(cCenter - center) >= btn.width / 2f
        }
    }

    fun updateCardHeight(h: Int) {
        wheelCardH = h
    }

    fun toggle() {
        if (wheelPanel.visibility == View.VISIBLE) hide() else show()
    }

    fun hide() {
        if (wheelPanel.visibility != View.VISIBLE) return
        onWheelPhase?.invoke(false)
        pendingWheelRealign = false
        lastCenteredItem = null
        hideUpperWheel(false)
        wheelPanel.animate().cancel()
        wheelPanel.visibility = View.GONE
        wheelPanel.translationY = 0f
        wheelPanel.alpha = 1f
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

    private fun show() {
        onWheelPhase?.invoke(true)
        wheelPanel.visibility = View.VISIBLE
        wheelPanel.translationY = 0f
        wheelPanel.alpha = 0f
        wheelPanel.animate().cancel()
        wheelPanel.animate().alpha(1f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
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
        upperWheelPanel.translationY = wheelCardH.toFloat()
        upperWheelPanel.alpha = 0f
        upperWheelPanel.animate().translationY(0f).alpha(1f).setDuration(160)
            .setInterpolator(DecelerateInterpolator()).start()
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
        if (animate) {
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
