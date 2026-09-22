package com.workspace.proot

import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import java.io.File

/**
 * Tab 介绍页叠加层：纯模态实心页，不切换 Tab、不改 currentTab/指示器/页面显隐。
 * 只挂在 MainActivity 顶栏五个 Tab 图标上；子 Activity 与磁贴独立笔记页没有这套 UI，
 * 天然不可触发。打开时拦截全部触摸，返回键逆向关闭。
 *
 * 布局是固定页眉（hero 槽位 + 标题）+ 下面独立滚动的正文；hero 是同一个 icon
 * 从 tab 直飞页眉槽位，中途无交棒，落点零闪烁。关闭时同一 icon 原路飞回。
 */
class TabIntroController(
    private val activity: MainActivity,
    private val root: FrameLayout,
    private val theme: ThemeColors,
    private val workspaceDir: File
) {
    private val density = activity.resources.displayMetrics.density

    private var overlay: FrameLayout? = null
    private var heroSlot: FrameLayout? = null
    private var heroView: ImageView? = null
    private var sourceView: ImageView? = null
    private var sysCard: SystemInfoCardView? = null
    private var sysLoadJob: Job? = null
    private var enterViews = mutableListOf<View>()
    private var closing = false

    fun isShowing(): Boolean = overlay != null

    /** 仅主界面顶栏调用，下标与 TabIntroContent.page 一致。 */
    fun attach(tabs: List<ImageView>) {
        tabs.forEachIndexed { i, iv ->
            iv.setOnClickListener { open(i, iv) }
        }
    }

    fun open(index: Int, source: ImageView) {
        if (overlay != null || closing) return
        activity.hideIme()
        val p = TabIntroContent.page(index)
        sourceView = source

        val ov = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(theme.surface)
            isClickable = true
            isFocusable = true
            alpha = 0f
        }
        // 固定页眉：hero 槽位留空给直飞来的 hero，标题在旁。
        val slot = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(120))
        }
        heroSlot = slot
        val headerBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(12))
            addView(slot)
            addView(TextView(activity).apply {
                text = activity.getString(p.titleRes)
                setTextColor(theme.onSurface)
                typeface = Typeface.DEFAULT_BOLD
                textSize = UiTokens.TEXT_TITLE
                setPadding(dp(16), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(48))
        }
        // 终端页：系统信息卡片（含饼图，进入后异步加载并 sweep）。
        if (p.showSystemInfo) {
            val card = SystemInfoCardView(activity, theme, workspaceDir)
            sysCard = card
            card.view.background = cardBg()
            col.addView(card.view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
            enterViews.add(card.view)
        }
        // 导语 + 分节卡片，全部可滚。
        col.addView(TextView(activity).apply {
            text = activity.getString(p.leadRes)
            setTextColor(theme.onSurface)
            textSize = UiTokens.TEXT_BODY
            setPadding(0, dp(16), 0, 0)
        })
        for (s in p.sections) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg()
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            card.addView(TextView(activity).apply {
                text = activity.getString(s.titleRes)
                setTextColor(theme.primary)
                typeface = Typeface.DEFAULT_BOLD
                textSize = UiTokens.TEXT_BODY
            })
            card.addView(TextView(activity).apply {
                text = activity.getString(s.bodyRes)
                setTextColor(theme.onSurface)
                textSize = UiTokens.TEXT_BODY
                setPadding(0, dp(6), 0, 0)
            })
            col.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
            enterViews.add(card)
        }
        for (v in enterViews) {
            v.alpha = 0f
            v.translationY = (24 * density)
        }

        val scroller = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
            addView(col)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(headerBar)
            addView(scroller)
        }
        ov.addView(container)
        overlay = ov
        root.addView(ov)
        // 焦点抢到叠加层：否则焦点留在底下 TerminalView，返回键会被它当 ESC 吃掉，
        // 到不了 OnBackPressedDispatcher，介绍页就关不掉。
        // ov 与 root 同为全屏原点，相对坐标可直接换算。
        ov.isFocusableInTouchMode = true
        ov.requestFocus()

        // 系统信息每次打开都是新卡片实例，数据到达后 sweep 自然重播。
        sysCard?.let { sysLoadJob = it.load(activity.lifecycleScope) }
        ov.post { startEnter(source) }
    }

    /** 返回键入口：正在展示则逆向关闭并消费事件，否则返回 false。 */
    fun close(): Boolean {
        val ov = overlay ?: return false
        if (closing) return true
        closing = true
        sysLoadJob?.cancel()
        sysLoadJob = null
        sysCard?.cancel()
        cancelFlight()
        val src = sourceView
        val hero = heroView
        if (src == null || hero == null || !src.isAttachedToWindow) {
            fadeOut(ov)
            return true
        }
        for (v in enterViews) {
            v.animate().cancel()
            v.animate().alpha(0f).setDuration(120).setInterpolator(DecelerateInterpolator()).start()
        }
        // 同一个 hero 原路飞回：落点就是它的布局矩形（= 来源矩形），同输入同输出；
        // 叠加层淡出露出真 tab 时再淡掉分身。
        ov.animate().cancel()
        ov.animate().alpha(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        hero.animate()
            .translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(240).setInterpolator(DecelerateInterpolator())
            .withEndAction { teardown() }.start()
        hero.animate().alpha(0f).setStartDelay(140).setDuration(100).start()
        return true
    }

    fun onDestroy() {
        sysLoadJob?.cancel()
        sysLoadJob = null
        sysCard?.cancel()
        sysCard = null
        cancelFlight()
        heroView?.let { dropHero(it) }
        heroView = null
        overlay?.let { runCatching { root.removeView(it) } }
        overlay = null
        closing = false
    }

    private fun startEnter(source: ImageView) {
        val ov = overlay ?: return
        val slot = heroSlot ?: return
        if (!source.isAttachedToWindow || !slot.isAttachedToWindow || slot.width <= 0) {
            ov.animate().alpha(1f).setDuration(200).start()
            playEnterStagger(0L)
            return
        }
        // 矢量克隆：同 drawable、同 tint、同 padding、同 scaleType、同矩形，
        // 起飞第一帧与 tab 渲染完全一致；全程矢量，放大不糊。
        val hero = cloneTabIcon(source)
        heroView = hero
        ov.addView(hero)
        ov.animate().alpha(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        playEnterStagger(140L)
        // 落点按槽位外接盒 FIT：五个 tab 落点一样大；等比，中心对齐。
        // 落定后 hero 原地成为页眉，无交棒。
        val slotRect = rectOf(slot)
        val srcRect = rectOf(source)
        val s = minOf(
            slotRect.width().toFloat() / srcRect.width().toFloat(),
            slotRect.height().toFloat() / srcRect.height().toFloat()
        )
        val dx = slotRect.exactCenterX() - srcRect.exactCenterX()
        val dy = slotRect.exactCenterY() - srcRect.exactCenterY()
        hero.animate()
            .translationX(dx).translationY(dy).scaleX(s).scaleY(s)
            .setDuration(300).setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 矢量克隆：渲染输入与来源完全一致，输出必然一致。 */
    private fun cloneTabIcon(source: ImageView): ImageView {
        return ImageView(activity).apply {
            setImageDrawable(source.drawable)
            colorFilter = source.colorFilter
            scaleType = source.scaleType
            setPadding(source.paddingLeft, source.paddingTop, source.paddingRight, source.paddingBottom)
            layoutParams = FrameLayout.LayoutParams(source.width, source.height)
            pivotX = source.width / 2f
            pivotY = source.height / 2f
        }.also { positionAt(it, source) }
    }

    private fun positionAt(v: View, anchor: View) {
        val r = rectOf(anchor)
        (v.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = r.left
            topMargin = r.top
        }
    }

    /** view 在 root 系中的矩形（overlay 全屏同原点，可直接换算）。 */
    private fun rectOf(v: View): Rect {
        val rootLoc = IntArray(2)
        val vLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        v.getLocationOnScreen(vLoc)
        val l = vLoc[0] - rootLoc[0]
        val t = vLoc[1] - rootLoc[1]
        return Rect(l, t, l + v.width, t + v.height)
    }

    private fun playEnterStagger(baseDelay: Long) {
        for ((i, v) in enterViews.withIndex()) {
            v.animate().alpha(1f).translationY(0f).setDuration(240)
                .setStartDelay(baseDelay + i * 60)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun fadeOut(ov: FrameLayout) {
        ov.animate().cancel()
        ov.animate().alpha(0f).setDuration(150).setInterpolator(DecelerateInterpolator())
            .withEndAction { teardown() }.start()
    }

    private fun dropHero(hero: ImageView) {
        runCatching { (hero.parent as? FrameLayout)?.removeView(hero) }
        if (heroView === hero) heroView = null
    }

    private fun cancelFlight() {
        overlay?.animate()?.cancel()
        heroView?.animate()?.cancel()
        for (v in enterViews) v.animate().cancel()
    }

    private fun teardown() {
        sysLoadJob?.cancel()
        sysLoadJob = null
        sysCard?.cancel()
        sysCard = null
        heroView?.let { dropHero(it) }
        heroView = null
        overlay?.let { runCatching { root.removeView(it) } }
        overlay = null
        heroSlot = null
        sourceView = null
        enterViews.clear()
        closing = false
        // 焦点还给终端（仅终端页）：打开前焦点就在它那，保持原有按键行为。
        runCatching {
            if (activity.currentTab == 0) activity.terminalController.terminalView.requestFocus()
        }
    }

    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        setColor(theme.surfaceVariant)
        cornerRadius = 20f * density
    }

    private fun dp(v: Int): Int = (v * density).toInt()
}
