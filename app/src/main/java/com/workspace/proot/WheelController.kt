package com.workspace.proot

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
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
    fun currentRecommendedGroupName(): String?
    fun groupStartPos(
        members: List<ShortcutItem.Command>,
        emptyCount: Int,
        base: Int,
        cycleSize: Int,
        useGlobal: Boolean
    ): Int
    fun execute(item: ShortcutItem)
}

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
    private val snapHelper = SkipEmptySnapHelper().apply { attachToRecyclerView(wheelRecycler) }
    private val upperSnapHelper = SkipEmptySnapHelper().apply { attachToRecyclerView(upperWheelRecycler) }

    private var lastDetectedTui = ""
    private var wheelStartPos = Int.MAX_VALUE / 2
    private var upperWheelAdapter: WheelAdapter? = null
    private var currentUpperGroup: String? = null
    private var pendingWheelRealign = false
    private var pendingUpperRealign = false
    private var pendingUpperGroup: ShortcutItem.Group? = null
    private var wheelBtnLight: GradientDrawable? = null
    private var wheelBtnDark: GradientDrawable? = null
    private var upperWheelBtnLight: GradientDrawable? = null

    init {
        wheelRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateWheelGlow()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && pendingWheelRealign) {
                    alignWheelToLanding()
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
    }

    fun updateCardHeight(h: Int) {
        wheelCardH = h
    }

    fun toggle() {
        if (wheelPanel.visibility == View.VISIBLE) hide() else show()
    }

    fun hide() {
        if (wheelPanel.visibility != View.VISIBLE) return
        pendingWheelRealign = false
        hideUpperWheel(false)
        wheelPanel.animate().cancel()
        wheelPanel.animate().translationY(wheelCardH.toFloat()).alpha(0f).setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                wheelPanel.visibility = View.GONE
                wheelPanel.translationY = 0f
                wheelPanel.alpha = 1f
            }.start()
    }

    fun onResume() {
        if (wheelPanel.visibility == View.VISIBLE) refreshAndApply(force = true, smooth = true, alignAlways = false)
    }

    fun refreshAll() {
        currentUpperGroup = null
        pendingWheelRealign = false
        pendingUpperRealign = false
        pendingUpperGroup = null
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
        wheelPanel.visibility = View.VISIBLE
        wheelPanel.translationY = wheelCardH.toFloat()
        wheelPanel.alpha = 0f
        wheelPanel.animate().translationY(0f).alpha(1f).setDuration(220)
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
        val lm = wheelRecycler.layoutManager as? LinearLayoutManager ?: return
        if (wheelBtnLight == null || wheelBtnDark == null) {
            val centerRadius = ButtonStyle.CORNER_RADIUS_DP * context.resources.displayMetrics.density
            wheelBtnLight = GradientDrawable().apply {
                setColor(Color.parseColor("#F2F2F2"))
                cornerRadius = centerRadius
            }
            wheelBtnDark = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
            }
        }
        val center = wheelRecycler.width / 2f
        for (i in 0 until lm.childCount) {
            val btn = lm.getChildAt(i) as? Button ?: continue
            if (btn.text.isEmpty()) continue
            val childCenter = btn.left + btn.width / 2f
            val dist = Math.abs(childCenter - center) / (btn.width / 2f)
            val isCenter = dist < 1f
            val scale = 1f - 0.14f * dist.coerceIn(0f, 1f)
            if (Math.abs(btn.scaleX - scale) > 0.01f) {
                btn.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            }
            btn.isSelected = isCenter
            btn.setTextColor(if (isCenter) Color.BLACK else Color.WHITE)
            btn.background = if (isCenter) wheelBtnLight else wheelBtnDark
            btn.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        syncUpperWheel()
    }

    private fun updateUpperWheelGlow() {
        val lm = upperWheelRecycler.layoutManager as? LinearLayoutManager ?: return
        if (upperWheelBtnLight == null) {
            val radius = 8 * context.resources.displayMetrics.density
            val centerRadius = ButtonStyle.CORNER_RADIUS_DP * context.resources.displayMetrics.density
            upperWheelBtnLight = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF3D6"))
                cornerRadius = centerRadius
            }
        }
        val center = upperWheelRecycler.width / 2f
        for (i in 0 until lm.childCount) {
            val btn = lm.getChildAt(i) as? Button ?: continue
            if (btn.text.isEmpty()) continue
            val childCenter = btn.left + btn.width / 2f
            val dist = Math.abs(childCenter - center) / (btn.width / 2f)
            val isCenter = dist < 1f
            val scale = 1f - 0.14f * dist.coerceIn(0f, 1f)
            if (Math.abs(btn.scaleX - scale) > 0.01f) {
                btn.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            }
            btn.isSelected = isCenter
            btn.setTextColor(if (isCenter) Color.BLACK else Color.WHITE)
            btn.background = if (isCenter) upperWheelBtnLight else wheelBtnDark
            btn.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    private fun syncUpperWheel() {
        if (wheelPanel.visibility != View.VISIBLE) {
            hideUpperWheel(false)
            return
        }
        val child = centeredChild(wheelRecycler) ?: return
        val tag = child.tag as? ShortcutItem ?: return
        val group = tag as? ShortcutItem.Group
        if (group == null || group.members.isEmpty()) {
            hideUpperWheel(true)
        } else {
            showUpperWheel(group)
        }
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
            centerColor = Color.parseColor("#FFF3D6"),
            glowColor = Color.parseColor("#FFE8A93C"),
            onHighlightClick = { item -> dataSource.execute(item) }
        )
        upperWheelRecycler.adapter = upperWheelAdapter
        upperSnapHelper.isEmptyCheck = { pos ->
            (upperWheelRecycler.adapter as? WheelAdapter)?.isEmpty(pos) ?: false
        }
        val cycleSize = members.size + (if (members.size <= 2) 1 else 0)
        val base = Int.MAX_VALUE / 2
        val emptyCount = if (members.size <= 2) 1 else 0
        val useGlobal = dataSource.currentRecommendedGroupName() != group.name
        val startPos = dataSource.groupStartPos(
            members, emptyCount, base, cycleSize, useGlobal = useGlobal
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
        val useGlobal = dataSource.currentRecommendedGroupName() != group.name
        val targetPos = dataSource.groupStartPos(
            members, emptyCount, base, cycle, useGlobal = useGlobal
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
