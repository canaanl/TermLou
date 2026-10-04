package com.workspace.proot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * 终端缝隙里的活动进度条 —— opencode v2 的工作进度条同款（[OpencodeRider]）。
 *
 * ## 它靠分层，不用算缝
 *
 * 本视图是 `terminalWrapper` 的**最底层**，铺满整个区域。上面两层会把它挡住：
 *
 * ```
 * 顶层  终端文字        TerminalView（自己不画背景）
 * 中层  终端背景        不透明，盖到第 mRows 行底部
 * 底层  ← 本视图        动画画在这里
 * ──────────────────
 *       快捷栏
 * ```
 *
 * 中层盖住的那部分本视图画什么都看不见，所以**裁剪由分层免费完成**：
 * 只有终端底边到快捷栏顶边之间那一条会露出来 —— 那就是缝隙。
 * 本视图因此**不需要知道缝隙有多宽**，只要把色块画满自己整个高度即可，
 * 露出来的那一段自然就是"缝隙被填满"。
 *
 * ## 什么时候有动画
 *
 * 只看 PTY 有没有输出：`TerminalController.onTextChanged()` 调 [onPtyOutput]。
 *
 * - **有输出**：画 opencode 那个进度条。
 * - **空闲 [IDLE_MS] 之后**：**清空成背景色**（不是冻结在某一帧）。
 *   清空后这个功能等于不存在，缝隙和现在的背景色一模一样。
 *   下次再有输出时从**第 0 帧**重新起 —— 静默时已经空了，接着上一轮会突兀。
 *
 * ## 不碰终端
 *
 * 叠在下面，不改 `terminalView` 的尺寸/位置/padding，PTY 的 rows/columns 一个字节不变。
 * 不向 PTY 写任何数据，不解析任何 TUI 状态。
 */
class TerminalActivityBar(context: Context) : View(context) {

    // ---------- 几何（由 TerminalController 喂进来，全是终端自己报的数） ----------

    /**
     * 缝隙顶边 = 终端第 `mRows` 行的底部（`terminalView.getPointY(mEmulator.mRows)`）。
     * 画布上 `[stripTop, height)` 就是露出来的那条缝。
     */
    private var stripTop = 0

    /** 终端的字符格宽（`mRenderer.getFontWidth()`），色块按它落在字符格边界上。 */
    private var cellWidth = 0f

    private var cells = 0

    // ---------- 颜色 ----------

    private var inkColor = 0xFF888888.toInt()

    private var trailColors = IntArray(0)

    /** 线条颜色 = 主题色的**可见变体**。不能用 `primary`：背景接近主题绿时它等于隐形。 */
    fun setInkColor(color: Int) {
        if (inkColor == color) return
        inkColor = color
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        trailColors = IntArray(OpencodeRider.TRAIL_STEPS) { step ->
            val boost = if (step == 1) OpencodeRider.GLARE_BOOST else 1f
            val alpha = (OpencodeRider.trailAlpha(step) * 255f).toInt().coerceIn(0, 255)
            (alpha shl 24) or
                (minOf(255f, r * boost).toInt() shl 16) or
                (minOf(255f, g * boost).toInt() shl 8) or
                minOf(255f, b * boost).toInt()
        }
        invalidate()
    }

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
        val next = if (width <= 0 || cellWidth <= 0f) 0 else (width / cellWidth).toInt()
        if (next != cells) {
            cells = next
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recountCells()
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
        frame = 0
        elapsedMs = 0L
        invalidate()
    }

    private val frameTask = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            // 用真实经过的时间换算帧号，掉帧时整体节奏仍对齐 opencode 的 40ms/帧
            val dt = (now - lastFrameUptime).coerceAtLeast(0L).coerceAtMost(250L)
            lastFrameUptime = now
            elapsedMs += dt
            frame = ((elapsedMs / OpencodeRider.INTERVAL_MS).toInt()) % OpencodeRider.TOTAL_FRAMES
            invalidate()
            postOnAnimationDelayed(this, OpencodeRider.INTERVAL_MS)
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
            // 静默时已经清空过，所以每次从第 0 帧重新起
            frame = 0
            elapsedMs = 0L
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
        if (!running || cells <= 0 || cellWidth <= 0f || trailColors.isEmpty()) return
        val bandH = height.toFloat()
        if (stripTop >= bandH) return                         // 没有缝隙可画

        rebuildPaths(frame)
        // 轨道：未激活格 = 同色 alpha 0.6 × 淡入淡出系数（opencode 的 defaultRgba）
        fillPaint.color = withAlpha(inkColor, (OpencodeRider.inactiveAlpha(frame) * 255f).toInt())
        canvas.drawPath(trackPath, fillPaint)
        for (step in 0 until OpencodeRider.TRAIL_STEPS) {
            val path = trailPaths[step] ?: continue
            fillPaint.color = trailColors[step]
            canvas.drawPath(path, fillPaint)
        }
    }

    /**
     * 每帧重建 7 条路径（1 条轨道 + 6 级拖尾）。
     *
     * 激活格的矩形从 `stripTop` 铺到本视图底边 —— 露出来的那一段正好就是**填满的缝隙**，
     * 高度自动等于缝隙高度，不用另外去算缝有多宽。
     * 未激活格画成居中短块（对应 opencode 的 `⬝` 比 `■` 小一圈）。
     */
    private fun rebuildPaths(currentFrame: Int) {
        val top = stripTop.toFloat()
        val bottom = height.toFloat()
        val bandH = bottom - top
        val inset = cellWidth * BLOCK_INSET_RATIO
        val blockW = cellWidth - inset * 2f
        val dotH = max(1f, bandH * TRACK_HEIGHT_RATIO)
        val dotTop = top + (bandH - dotH) / 2f
        trackPath.reset()
        trailPaths.forEach { it?.reset() }
        for (i in 0 until cells) {
            val step = OpencodeRider.trailIndex(currentFrame, i % OpencodeRider.WIDTH)
            val left = i * cellWidth + inset
            if (step >= 0) {
                val path = trailPaths[step] ?: Path().also { trailPaths[step] = it }
                path.addRect(left, top, left + blockW, bottom, Path.Direction.CW)
            } else {
                trackPath.addRect(left, dotTop, left + blockW, dotTop + dotH, Path.Direction.CW)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

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

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPath = Path()
    private val trailPaths = arrayOfNulls<Path>(OpencodeRider.TRAIL_STEPS)

    private var frame = 0
    private var elapsedMs = 0L
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
        setInkColor(inkColor)
    }

    companion object {
        /**
         * 方块左右各留一点缝，免得连成一片看不出"格"。
         * 这是唯一一处**故意不铺满**的地方；纵向一定从缝隙顶铺到缝隙底，铺满。
         */
        private const val BLOCK_INSET_RATIO = 0.18f

        /** 未激活轨道块的高度占缝隙高度的比例（居中），对应 opencode 的 `⬝` 比 `■` 小一圈。 */
        private const val TRACK_HEIGHT_RATIO = 0.34f

        /** PTY 空闲多久后清空。够短能接住 TUI 连续重绘，够长不会在敲完命令后一直转。 */
        const val IDLE_MS = 800L
    }
}
