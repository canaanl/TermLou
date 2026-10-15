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
 * - **等宽等高**：所有条**一样宽**，高度 = **整个缝隙高度**，一个像素不差。
 *   格子之间**只有明暗不同**，没有高矮之分。
 * - **铺满全宽**（5.9.57）：条数按「字符格 × 1.1」向下取整，除不尽的余量
 *   **匀进每条缝里**而不是丢掉 —— 丢掉的话最右边会永远缺一截（1080px 屏上
 *   实测余量 9.5px ≈ 0.96 条，正是用户看到的「最右边总是少一个方块」）。
 *   代价是条宽不再精确等于一个字符格，最多偏宽 11%（实测 1080px 上偏 0.9%）。
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
        val next = countCells(width, cellWidth)
        if (next != cells) {
            cells = next
            invalidate()
        }
    }

    /** 实际排布间距（含 10% 缝）= 全宽 ÷ 条数 —— 余量匀进缝里，所以右端铺满。 */
    private fun pitch(): Float = trackPitch(width, cells)

    /** 条宽 = 间距去掉那条 10% 的缝。 */
    private fun barWidth(): Float = trackBarWidth(pitch())

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
            // 5.9.57：跟 vsync 走，不再自己节流。原来写死 16ms（60fps），
            // 在 120Hz 屏上 16ms > 一个 vsync 周期 8.33ms，会落到隔一帧 → 仍然只有 60fps，
            // 变成全 app 唯一慢半拍的动画。位置本来就是按真实时间算的（见 elapsedMs），
            // 帧率只影响平滑度，所以延时 0 就是最优解，60Hz 设备也照样 60。
            postOnAnimation(this)
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
            canvas.drawRect(cellLeft(i), top, cellLeft(i) + barWidth(), bottom, fillPaint)
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
            canvas.drawRect(cellLeft(i), top, cellLeft(i) + barWidth(), bottom, fillPaint)
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
         * 只用来算**名义**间距（定条数）；实际排布由 [trackPitch] 匀满全宽。
         */
        private const val GAP_RATIO = 0.1f

        /**
         * 名义间距 = 字符格 × (1 + 缝占比)。条宽取一个字符格，缝占条宽的 10%。
         * 这个值除不尽全宽，末尾余量由 [trackPitch] 匀进缝里，不丢弃。
         */
        fun nominalPitch(cellWidth: Float): Float = cellWidth * (1f + GAP_RATIO)

        /** 条数 = 全宽 ÷ 名义间距，向下取整（不够一整条就不画）。 */
        fun countCells(width: Int, cellWidth: Float): Int =
            if (width <= 0 || cellWidth <= 0f) 0 else (width / nominalPitch(cellWidth)).toInt()

        /**
         * 实际排布间距 = 全宽 ÷ 条数（5.9.57）。
         *
         * 条数是向下取整来的，所以名义间距一定 ≤ 这个值：余量被匀进每一条缝里，
         * 条因此略宽于一个字符格（最多 +11%），右端则铺满，和条间缝一样收尾。
         */
        fun trackPitch(width: Int, cells: Int): Float =
            if (cells <= 0) 0f else width.toFloat() / cells

        /** 条宽 = 间距 ÷ (1 + 缝占比)，缝仍是条宽的 10%。 */
        fun trackBarWidth(pitch: Float): Float = pitch / (1f + GAP_RATIO)

        /** PTY 空闲多久后清空。够短能接住 TUI 连续重绘，够长不会在敲完命令后一直转。 */
        const val IDLE_MS = 800L
    }
}