package com.workspace.proot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
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
        // 焦点抢到叠加层：否则焦点留在底下 TerminalView，返回键会被它当 ESC 吃掉，
        // 到不了 OnBackPressedDispatcher，介绍页就关不掉。
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
        // 分身实拍页眉（方形 tint 图标），等比缩回 tab 中心；叠加层同步淡出露出原 tab。
        val fly = makeFly(head, snapshot(head), p.iconRes, head.colorFilter)
        root.addView(fly)
        flyView = fly
        val (dx, dy) = centerDelta(head, src)
        val s = src.height.toFloat() / head.height.toFloat()
        ov.animate().cancel()
        ov.animate().alpha(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        fly.animate()
            .translationX(dx).translationY(dy).scaleX(s).scaleY(s)
            .setDuration(240).setInterpolator(DecelerateInterpolator())
            .withEndAction { teardown() }.start()
        fly.animate().alpha(0f).setStartDelay(140).setDuration(100).start()
        return true
    }

    fun onDestroy() {
        sysLoadJob?.cancel()
        sysLoadJob = null
        sysCard?.cancel()
        sysCard = null
        cancelFlight()
        flyView?.let { dropFly(it) }
        flyView = null
        overlay?.let { runCatching { root.removeView(it) } }
        overlay = null
        closing = false
    }

    private fun startEnter(source: ImageView) {
        val ov = overlay ?: return
        val head = headerIcon ?: return
        val p = page ?: return
        // 页眉继承来源 tab 的 tint：深色模式选中态是白色，不同步就会黑白不一致。
        head.colorFilter = source.colorFilter
        if (!source.isAttachedToWindow || !head.isAttachedToWindow ||
            source.width <= 0 || head.width <= 0
        ) {
            ov.animate().alpha(1f).setDuration(200).start()
            head.alpha = 1f
            playEnterStagger(0L)
            return
        }
        // 分身用来源 view 的实拍快照：tint/padding/原比例一次性烘焙，起飞零跳变。
        val fly = makeFly(source, snapshot(source), p.iconRes, source.colorFilter)
        root.addView(fly)
        flyView = fly
        ov.animate().alpha(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        playEnterStagger(140L)
        // 等比缩放 + 中心对中心位移：全程不变形；页眉在落点前交叉淡入，接棒无断层。
        val (dx, dy) = centerDelta(source, head)
        val s = head.height.toFloat() / source.height.toFloat()
        head.animate().alpha(1f).setStartDelay(190).setDuration(130).start()
        fly.animate()
            .translationX(dx).translationY(dy).scaleX(s).scaleY(s)
            .setDuration(300).setInterpolator(DecelerateInterpolator())
            .withEndAction { dropFly(fly) }.start()
    }

    /** hero 分身：优先用实拍快照（所见即所得），快照失败才回退 drawable + 手工 tint。 */
    private fun makeFly(anchor: View, bmp: Bitmap?, iconRes: Int, tint: ColorFilter?): ImageView {
        return ImageView(activity).apply {
            if (bmp != null) {
                setImageBitmap(bmp)
            } else {
                setImageResource(iconRes)
                colorFilter = tint
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(anchor.width, anchor.height)
            pivotX = anchor.width / 2f
            pivotY = anchor.height / 2f
        }.also { positionAt(it, anchor) }
    }

    private fun snapshot(v: View): Bitmap? {
        if (v.width <= 0 || v.height <= 0 || !v.isAttachedToWindow) return null
        return runCatching {
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { bmp ->
                v.draw(Canvas(bmp))
            }
        }.getOrNull()
    }

    private fun positionAt(v: View, anchor: View) {
        val rootLoc = IntArray(2)
        val anchorLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        anchor.getLocationOnScreen(anchorLoc)
        (v.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = anchorLoc[0] - rootLoc[0]
            topMargin = anchorLoc[1] - rootLoc[1]
        }
    }

    /** 目标中心减起点中心（相对 root 坐标）：配合中心 pivot，位移即中心对齐。 */
    private fun centerDelta(from: View, to: View): Pair<Float, Float> {
        val rootLoc = IntArray(2)
        val fromLoc = IntArray(2)
        val toLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        from.getLocationOnScreen(fromLoc)
        to.getLocationOnScreen(toLoc)
        val fx = fromLoc[0] - rootLoc[0] + from.width / 2f
        val fy = fromLoc[1] - rootLoc[1] + from.height / 2f
        val tx = toLoc[0] - rootLoc[0] + to.width / 2f
        val ty = toLoc[1] - rootLoc[1] + to.height / 2f
        return (tx - fx) to (ty - fy)
    }

    private fun playEnterStagger(baseDelay: Long) {
        for ((i, v) in enterViews.withIndex()) {
            v.animate().alpha(1f).translationY(0f).setDuration(240)
                .setStartDelay(baseDelay + i * 60)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun dropFly(fly: ImageView) {
        runCatching { root.removeView(fly) }
        runCatching { (fly.drawable as? BitmapDrawable)?.bitmap?.recycle() }
        if (flyView === fly) flyView = null
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
        flyView?.let { dropFly(it) }
        flyView = null
        overlay?.let { runCatching { root.removeView(it) } }
        overlay = null
        headerIcon = null
        sourceView = null
        enterViews.clear()
        page = null
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
