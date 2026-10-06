package com.workspace.proot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 终端缝隙里的活动条 —— 匀速单焦点横向扫描。
 *
 * ## 它靠分层，不用算缝
 *
 * 本视图是 `terminalWrapper` 的**最底层**，铺满整个区域。上面两层会把它挡住：
 *
 * ```
 * 顶层  终端文字    TerminalView（自己不画背景）
 * 中层  终端背景    不透明，盖到第 mRows 行底部
 * 底层  ← 本视图    活动条画在这里
 * ──────────────────
 *       快捷栏
 * ```
 *
 * 中层盖住的部分本视图画什么都看不见，所以**裁剪由分层免费完成**：
 * 只有终端底边到快捷栏顶边之间那一条会露出来 —— 那就是缝隙。
 * 本视图因此**不需要知道缝隙有多宽**，把格子铺满自己整个高度即可，
 * 露出来的那一段自然就是"填满的缝隙"。
 *
 * ## 样子
 *
 * - **等宽等高**：每格宽度 = 一个字符格，高度 = **整个缝隙高度**，一个像素不差。
 *   格子之间**只有明暗不同**，没有高矮之分。
 * - **单焦点**：整条轨道只有**一个**亮点，其余是它身后渐暗的拖尾。
 *   焦点从左匀速走到右，停一下，折回，再停一下（往复）。
 * - **不跳格**：亮度按「格子中心到焦点的连续距离」算，焦点位置是浮点像素
 *   （见 [ActivityTrail]）。opencode 原版按格号取模，那是 8 格小单元的做法；
 *   全宽 73 格照搬会变成每帧跳 9 格。
 *
 * ## 什么时候有动画
 *
 * 只看 PTY 有没有输出：`TerminalController.onTextChanged()` 调 [onPtyOutput]。
 *
 * - **有输出**：画活动条。
 * - **空闲 [IDLE_MS] 之后**：**清空成背景色**（不是冻结在某一帧）。
 *   清空后这个功能等于不存在，缝隙和现在的背景色一模一样。
 *   下次再有输出时**从周期开头**重新起。
 *
 * ## 不碰终端
 *
 * 叠在下面，不改 `terminalView` 的尺寸/位置/padding，PTY 的 rows/columns 一个字节不变。
 * 不向 PTY 写任何数据，不解析任何 TUI 状态。
 */
class TerminalActivityBar(context: Context) : View(context) {

    // ---------- 几何（由 TerminalController 喂进来，全是终端自己报的数） ----------

    /** 缝隙顶边 = 终端第 `mRows` 行底部（`terminalView.getPointY(mEmulator.mRows)`）。 */
    private var stripTop = 0

    /** 终端的字符格宽（`mRenderer.getFontWidth()`），格子按它落在字符格边界上。 */
    private var cellWidth = 0f

    private var cells = 0

    fun setGeometry(stripTopPx: Int, cellWidthPx: Float) {
        if (stripTopPx != stripTop) {
            stripTop = stripTopPx
            invalidate()
        }
        if (cellWidthPx > 0f && cellWidthPx != cellWidth) {
            cellWidth = cellWidthPx
            invalidate()
        }
        recountCells()
    }

    private fun recountCells() {
        // 条间距 = 条宽 × 1.1：条与条之间留一条缝（缝 ≈ 条宽的 10%，缝里透背景色）。
        // 条数向下取整 —— 末尾不足一条的余量直接丢掉，不画半截条。
        val next = if (width <= 0 || cellWidth <= 0f) 0 else (width / pitch()).toInt()
        if (next != cells) {
            cells = next
            invalidate()
        }
    }

    /** 条间距（含 10% 缝）。条本身还是一个字符格宽（不断条宽，只加缝）。 */
    private fun pitch(): Float = cellWidth * (1f + GAP_RATIO)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recountCells()
    }

    // ---------- 颜色 ----------

    private var inkColor = 0xFF888888.toInt()

    /** 线条颜色 = 主题色的**可见变体**。不能用 `primary`：背景接近主题绿时它等于隐形。 */
    fun setInkColor(color: Int) {
        if (inkColor == color) return
        inkColor = color
        invalidate()
    }

    // ---------- PTY 活动 → 动/停 ----------

    /**
     * 报一次"PTY 有输出"。**任意线程可调**（termux 在自己的输出线程上直接调
     * `TerminalSessionClient.onTextChanged`），所以只写原子变量、只在需要时 post 一次。
     */
    fun onPtyOutput() {
        lastOutputUptime.set(SystemClock.uptimeMillis())
        if (running) return                                   // 已经在动，冻结计时链自己会续
        if (startPosted.compareAndSet(false, true)) post(startTask)
    }

    /** 切走 tab / onPause / 视图不可见：立刻停，并且清空。 */
    fun stop() {
        running = false
        removeCallbacks(frameTask)
        removeCallbacks(startTask)
        removeCallbacks(stopTask)
        stopScheduled = false
        startPosted.set(false)
        clearToBackground()
    }

    /**
     * 抹掉最后一帧，回到"什么都没有"的状态。
     *
     * ⚠ 清空**不是**冻结在当前帧：用户明确要求静默时看到的就是背景色，
     * 所以空闲和停机两条路径都归到这一个方法，免得只改一处漏一处。
     * `invalidate()` 必须有 —— 不重画的话最后一帧会一直留在屏幕上。
     */
    private fun clearToBackground() {
        elapsedMs = 0f
        invalidate()
    }

    private val frameTask = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastFrameUptime).coerceAtLeast(0L).coerceAtMost(100L)
            lastFrameUptime = now
            // 周期取模 → 天然无缝循环，不需要像原版那样拼 54 帧序列
            elapsedMs = (elapsedMs + dt) % ActivityTrail.CYCLE_MS
            invalidate()
            postOnAnimationDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private val startTask = Runnable {
        startPosted.set(false)
        resumeIfRecentOutput()
    }

    private val stopTask = object : Runnable {
        override fun run() {
            val idleFor = SystemClock.uptimeMillis() - lastOutputUptime.get()
            if (idleFor < IDLE_MS) {
                postDelayed(this, IDLE_MS - idleFor)           // 还有输出在来
            } else {
                stopScheduled = false
                running = false
                removeCallbacks(frameTask)
                clearToBackground()          // 清空成背景色，而不是冻结在当前帧
            }
        }
    }

    private fun resumeIfRecentOutput() {
        val idleFor = SystemClock.uptimeMillis() - lastOutputUptime.get()
        if (idleFor >= IDLE_MS) return                        // 唤起时已经安静了
        if (!running) {
            running = true
            // 静默时已经清空过，所以每次从周期开头重新起
            elapsedMs = 0f
            lastFrameUptime = SystemClock.uptimeMillis()
            removeCallbacks(frameTask)
            postOnAnimationDelayed(frameTask, 0L)
        }
        if (!stopScheduled) {
            stopScheduled = true
            postDelayed(stopTask, IDLE_MS - idleFor)
        }
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 静默时**什么都不画** → 底下透出根容器的 cSurface，和现在的背景色完全一致
        if (!running || cells <= 1 || cellWidth <= 0f) return
        val top = stripTop.toFloat()
        val bottom = height.toFloat()
        if (top >= bottom) return                            // 没有缝隙可画

        val focus = ActivityTrail.focusCell(cells, elapsedMs)
        val forward = ActivityTrail.isMovingForward(elapsedMs)
        val focusIndex = kotlin.math.round(focus).toInt()

        // 第 1 步：整条轨道铺一层暗色底（opencode 的 inactive 格）。
        // 必须先铺底，因为拖尾之外的格子 alpha 会算成 0 —— 不铺底的话那些格子是**全透明**的，
        // 缝隙会一段一段空掉，看上去就又变成"高度不一致"了。
        // 底也按条画（含 10% 缝），和亮条对齐 —— 缝里透的是背景色，不是暗色。
        val trackColor = withAlpha(
            inkColor,
            (ActivityTrail.trackAlpha(elapsedMs) * 255f).toInt().coerceIn(0, 255)
        )
        fillPaint.color = trackColor
        for (i in 0 until cells) {
            canvas.drawRect(cellLeft(i), top, cellLeft(i) + cellWidth, bottom, fillPaint)
        }

        // 第 2 步：逐格叠拖尾明暗。
        // 等宽等高：每格都从缝隙顶铺到缝隙底，**不做任何居中、不留边**，只有明暗不同。
        for (i in 0 until cells) {
            // 格子中心到焦点的有向距离（格坐标）。焦点是浮点 → 不会跳格
            val distance = ActivityTrail.signedDistance(focus, i.toFloat(), forward)
            val alpha = ActivityTrail.alphaAt(distance, elapsedMs)
            if (alpha <= 0.004f) continue                  // 暗色底已经铺好，这格不用再叠
            val rgb = boosted(i == focusIndex + glareOffset(forward), inkColor, alpha)
            fillPaint.color = withAlpha(rgb, (alpha * 255f).toInt().coerceIn(0, 255))
            canvas.drawRect(cellLeft(i), top, cellLeft(i) + cellWidth, bottom, fillPaint)
        }
    }

    private fun glareOffset(forward: Boolean): Int = if (forward) 1 else -1

    /** 焦点后一格泛光提亮（opencode 的 `GLARE_BOOST`）：只提亮 RGB，不动 alpha。 */
    private fun boosted(glare: Boolean, color: Int, alpha: Float): Int {
        if (!glare || alpha <= 0.004f) return color
        val boost = ActivityTrail.GLARE_BOOST
        val r = minOf(255f, ((color shr 16) and 0xFF) * boost).toInt()
        val g = minOf(255f, ((color shr 8) and 0xFF) * boost).toInt()
        val b = minOf(255f, (color and 0xFF) * boost).toInt()
        return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    private fun cellLeft(i: Int): Float = i * pitch()

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha shl 24) or (color and 0x00FFFFFF)

    // ---------- 可见性 ----------

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) stop()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    // ---------- 字段 ----------

    private val fillPaint = Paint()

    /** 周期内已过毫秒，取模后天然无缝循环。 */
    private var elapsedMs = 0f

    private var running = false
    private var stopScheduled = false
    private var lastFrameUptime = 0L
    private val lastOutputUptime = AtomicLong(0L)
    private val startPosted = AtomicBoolean(false)

    init {
        // 不设背景：静默时必须完全透明，才能透出底层的背景色
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    companion object {
        /**
         * 条间距里的缝占比（5.9.24）：缝 ≈ 条宽的 10%，缝里透背景色。
         * 条本身还是一个字符格宽 —— 不断条宽，只加缝；条数因此变少，自动重算。
         */
        private const val GAP_RATIO = 0.1f
        /**
         * 60fps。1.5s 一趟 ÷ 73 格 ≈ 每帧 0.9 格 —— 25fps 下会是每帧 3.6 格，看得出跳。
         */
        private const val FRAME_INTERVAL_MS = 16L

        /** PTY 空闲多久后清空。够短能接住 TUI 连续重绘，够长不会在敲完命令后一直转。 */
        const val IDLE_MS = 800L
    }
}