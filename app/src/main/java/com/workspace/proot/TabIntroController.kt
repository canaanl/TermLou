package com.workspace.proot

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
 */
class TabIntroController(
    private val activity: MainActivity,
    private val root: FrameLayout,
    private val theme: ThemeColors,
    private val workspaceDir: File
) {
    private val density = activity.resources.displayMetrics.density

    private var overlay: FrameLayout? = null
    private var headerIcon: ImageView? = null
    private var sourceView: ImageView? = null
    private var flyView: ImageView? = null
    private var sysCard: SystemInfoCardView? = null
    private var sysLoadJob: Job? = null
    private var enterViews = mutableListOf<View>()
    private var page: TabIntroPage? = null
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
        page = p
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
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(48))
        }

        // 页眉：Tab icon（hero 落点）+ 标题，紧凑一行。
        val headRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(activity).apply {
            setImageResource(p.iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            alpha = 0f
        }
        headerIcon = icon
        headRow.addView(icon)
        headRow.addView(TextView(activity).apply {
            text = activity.getString(p.titleRes)
            setTextColor(theme.onSurface)
            typeface = Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_TITLE
            setPadding(dp(16), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        col.addView(headRow)

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
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            addView(col)
        }
        ov.addView(scroller)
        overlay = ov
        root.addView(ov)

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
        val head = headerIcon
        val p = page
        if (src == null || head == null || p == null ||
            !src.isAttachedToWindow || !head.isAttachedToWindow ||
            src.width <= 0 || head.width <= 0
        ) {
            ov.animate().cancel()
            ov.animate().alpha(0f).setDuration(150).setInterpolator(DecelerateInterpolator())
                .withEndAction { teardown() }.start()
            return true
        }
        for (v in enterViews) {
            v.animate().cancel()
            v.animate().alpha(0f).setDuration(120).setInterpolator(DecelerateInterpolator()).start()
        }
        head.alpha = 0f
        // 分身摆在 header 矩形，正向动画回 tab 矩形。
        val fly = makeFlyAt(head, p.iconRes)
        root.addView(fly)
        flyView = fly
        val rootLoc = IntArray(2)
        val srcLoc = IntArray(2)
        val headLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        src.getLocationOnScreen(srcLoc)
        head.getLocationOnScreen(headLoc)
        val dx = (srcLoc[0] - rootLoc[0]) - (headLoc[0] - rootLoc[0])
        val dy = (srcLoc[1] - rootLoc[1]) - (headLoc[1] - rootLoc[1])
        ov.animate().cancel()
        ov.animate().alpha(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        fly.animate()
            .translationX(dx.toFloat()).translationY(dy.toFloat())
            .scaleX(src.width.toFloat() / head.width.toFloat())
            .scaleY(src.height.toFloat() / head.height.toFloat())
            .setDuration(240).setInterpolator(DecelerateInterpolator())
            .withEndAction { teardown() }.start()
        return true
    }

    fun onDestroy() {
        sysLoadJob?.cancel()
        sysLoadJob = null
        sysCard?.cancel()
        sysCard = null
        cancelFlight()
        flyView?.let { runCatching { root.removeView(it) } }
        flyView = null
        overlay?.let { runCatching { root.removeView(it) } }
        overlay = null
        closing = false
    }

    private fun startEnter(source: ImageView) {
        val ov = overlay ?: return
        val head = headerIcon ?: return
        val p = page ?: return
        if (!source.isAttachedToWindow || !head.isAttachedToWindow) {
            ov.animate().alpha(1f).setDuration(200).start()
            for ((i, v) in enterViews.withIndex()) {
                v.animate().alpha(1f).translationY(0f).setDuration(220)
                    .setStartDelay((80 + i * 50).toLong()).start()
            }
            head.alpha = 1f
            return
        }
        val fly = makeFlyAt(source, p.iconRes)
        root.addView(fly)
        flyView = fly
        ov.animate().alpha(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        for ((i, v) in enterViews.withIndex()) {
            v.animate().alpha(1f).translationY(0f).setDuration(240)
                .setStartDelay((140 + i * 60).toLong())
                .setInterpolator(DecelerateInterpolator()).start()
        }
        // fly 从 source 矩形起飞，落到 header 矩形：位移 + 等比缩放。
        val rootLoc = IntArray(2)
        val srcLoc = IntArray(2)
        val headLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        source.getLocationOnScreen(srcLoc)
        head.getLocationOnScreen(headLoc)
        val dx = (headLoc[0] - rootLoc[0]) - (srcLoc[0] - rootLoc[0])
        val dy = (headLoc[1] - rootLoc[1]) - (srcLoc[1] - rootLoc[1])
        val sx = head.width.toFloat() / source.width.toFloat()
        val sy = head.height.toFloat() / source.height.toFloat()
        fly.animate()
            .translationX(dx.toFloat()).translationY(dy.toFloat())
            .scaleX(sx).scaleY(sy)
            .setDuration(280).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                head.alpha = 1f
                runCatching { root.removeView(fly) }
                if (flyView === fly) flyView = null
            }.start()
    }

    /**
     * 构造 hero 分身：按 anchor 的矩形排布（pivot 左上，位移/缩放归零）。
     * open：anchor=tab 图标，再动画到 header；close：anchor=header，再动画回 tab。
     */
    private fun makeFlyAt(anchor: View, iconRes: Int): ImageView {
        val rootLoc = IntArray(2)
        val anchorLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        anchor.getLocationOnScreen(anchorLoc)
        return ImageView(activity).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = FrameLayout.LayoutParams(anchor.width, anchor.height).apply {
                leftMargin = anchorLoc[0] - rootLoc[0]
                topMargin = anchorLoc[1] - rootLoc[1]
            }
            pivotX = 0f
            pivotY = 0f
        }
    }

    private fun cancelFlight() {
        overlay?.animate()?.cancel()
        flyView?.animate()?.cancel()
        for (v in enterViews) v.animate().cancel()
    }

    private fun teardown() {
        sysLoadJob?.cancel()
        sysLoadJob = null
        sysCard?.cancel()
        sysCard = null
        flyView?.let { runCatching { root.removeView(it) } }
        flyView = null
        overlay?.let { runCatching { root.removeView(it) } }
        overlay = null
        headerIcon = null
        sourceView = null
        enterViews.clear()
        page = null
        closing = false
    }

    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        setColor(theme.surfaceVariant)
        cornerRadius = 20f * density
    }

    private fun dp(v: Int): Int = (v * density).toInt()
}
