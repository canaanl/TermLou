package com.workspace.proot

import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import java.io.File

/**
 * 鐘舵€佹爮鎬荤嚎锛歴tatusText 瑙嗗浘銆佺粓绔复鏃剁姸鎬併€佽法 Tab 鎻愮ず涓?generation 浠茶銆? * 鍘?MainActivity 鐘舵€佺浉鍏抽€昏緫鏀跺綊姝ゅ锛涘綋鍓?Tab 璇诲涓伙紙鍞竴鐪熺浉婧愶級銆? */
class StatusController(
    private val activity: MainActivity,
    private val scope: AppScope
) {
    lateinit var statusView: TextView
        private set

    /**
     * 5.9.46 修黑屏：安全访问状态栏正文。
     *
     * `statusView` 是 `lateinit`，要到 `MainActivity` 建视图时（createStatusWrap，
     * MainActivity.kt:213）才赋值。而 `refreshStatusBar()` 会被 `onResume` 调到 ——
     * **首次启动时 onResume 也会跑**，那条路径上 `statusView` 可能还没建。
     *
     * 直接访问未初始化的 `lateinit` 会抛 `UninitializedPropertyAccessException`
     * → onResume 炸 → **静默杀进程 → 黑屏**。本项目已经栽过两次同样的坑：
     * - 5.9.24：`child already has a parent`，onCreate 炸 → 黑屏 + 磁贴全死
     * - 5.9.45：5.9.45 新加的 `clearPersistent(statusController.statusView)` 漏了保护
     *
     * 所以：**所有跨模块访问 statusView 的地方都必须走这里**，不能直接摸属性。
     */
    fun statusViewOrNull(): TextView? = if (::statusView.isInitialized) statusView else null

    private var statusGen = 0
    private var lastClickedInfo = ""
    private var terminalTempActive = false
    private var terminalTempJob: Job? = null
    private var settingsStatusJob: Runnable? = null

    fun createStatusBar(): TextView {
        statusView = scope.uiBuilder.createStatusBar()
        return statusView
    }

    private var statusWrap: LinearLayout? = null

    /** 5.9.47：块① 特征字（Terminal/Notes/Files/Network/Settings），固定宽度 + 特征色。 */
    private lateinit var tabLabel: TextView

    /**
     * 5.9.47：两块共用的上下内距（px，启动时算一次）。
     *
     * 此前块② 的内距是 `(16, 8, 16, 0)` —— **底部 0**。单块时父容器是 FrameLayout
     * 能撑满，看不出来；拆成两块后各自按内容定高 + 垂直居中，
     * 下伸字母（`Settings` 的 g、`next key` 的 y）就超出盒子上沿被切
     * （用户实机报"字母下半截显示不出来"）。
     *
     * 上下必须**对称**：一边 8 一边 0 不叫内距，叫偏心。
     */
    private val STATUS_V_PAD: Int by lazy { activity.dp(UiTokens.SPACE_2) }

    /**
     * 5.9.47：**状态栏改成两块**。
     *
     * ```
     * ┌──────────────┬────────────────────────────────┐
     * │Files         │  /workspace/notes/long/pa… ←滚 │
     * └──────────────┴────────────────────────────────┘
     *   块① 固定          块② 滚动
     * ```
     *
     * - **视觉上是一整条**：背景色画在父容器上，两个子块都透明；无线、无缝、零分块感。
     * - **逻辑上是两块**：块① 只放特征字，宽度固定（取五个词里最宽的），左对齐，永不滚动；
     *   块② 放实际内容，自己跑自己的横向跑马灯。
     *
     * ## 为什么这么改（一次性解决三件用户实机报的事）
     *
     * 1. **字母下半截被切**（`Settings` 的 g、`next key` 的 y）：根因是长路径跑马灯用的
     *    `isSingleLine = true` 从没被真正关掉（`setSingleLine(false)` 在新版本上是废的），
     *    于是进过文件页之后**永久单行**，下伸字母一律被切。
     *    拆成两块后，块① 块② 各自按内容定高、各自垂直居中，**根本不需要开单行模式**。
     * 2. **滚动时 `Files |` 不是特征色**：根因是 `showPersistent` 直接 `view.text = full`
     *    绕过了上色。块① 独立存在，永远是特征色，滚的只有块②。
     * 3. **切走再切回来就不滚了**：根因是 `showPersistent` 拿 `view.width` 判断放不放得下，
     *    布局未完成时 `width` 为 0 → 误判成"放得下"。块② 只跟自己比，不受整条影响。
     *
     * 也顺带把 5.9.43/5.9.44 返工两轮的根源（"盖板必须与正文逐项一致"）从结构上拿掉了：
     * 盖板本来就是"用第二层假装第一层不动"，现在**真的不动**了，就不需要假装。
     */
    fun createStatusWrap(): LinearLayout {
        statusWrap?.let { return it }
        val theme = scope.theme
        // 背景画在父容器上：两块子 View 全透明 → 看起来就是一条，看不出分块。
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.surfaceContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        tabLabel = TextView(activity).apply {
            setTextColor(theme.statusAccent)
            textSize = UiTokens.TEXT_COMPACT
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = true
            // 5.9.47：**上下内距必须对称**。此前底部是 0，两块在 LinearLayout 里各自
            // 按内容定高时，下伸字母（Settings 的 g、next key 的 y）会超出盒子上沿被切 ——
            // 用户实机报"字母下半截显示不出来"。
            // 左内距 16dp 让特征字离屏幕边缘有呼吸；右侧不留内距 ——
        // 块① 的**宽度**里已经含了到块② 的间隔（见 measureTabWidth）。
        setPadding(activity.dp(UiTokens.SPACE_4), STATUS_V_PAD, 0, STATUS_V_PAD)
            // 宽度由 measureTabWidth() 定死（五个词里最宽的），换页签不跳
            layoutParams = LinearLayout.LayoutParams(measureTabWidth(), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        createStatusBar()
        // 5.9.47：间隔已含在块① 的宽度里，这里**不再加 marginStart**，免得翻倍。
        statusView.layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f,
        )

        wrap.addView(tabLabel)
        wrap.addView(statusView)
        return wrap.also { statusWrap = it }
    }

    /**
     * 块① 的固定宽度 = 五个特征字里最宽的那个。
     *
     * 用同一字号量一遍取最大值（`Settings`/`Terminal` 最宽），一次性定死，
     * 之后换页签**宽度不变**，所以视觉上不会出现"文字跳一下"。
     */
    private fun measureTabWidth(): Int {
        val p = android.text.TextPaint().apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                UiTokens.TEXT_COMPACT,
                activity.resources.displayMetrics,
            )
            typeface = android.graphics.Typeface.DEFAULT
        }
        var w = 0f
        for (name in StatusAccent.TAB_NAMES) w = maxOf(w, p.measureText(name))
        return activity.dp(UiTokens.SPACE_4) + w.toInt() + activity.dp(UiTokens.SPACE_2)
    }

    internal fun gen(): Int = statusGen

    internal fun bumpGen() {
        statusGen++
    }

    fun snack(text: String, long: Boolean = false) {
        if (!::statusView.isInitialized) return
        Snackbar.make(
            statusView,
            text,
            if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        ).show()
    }

    fun terminalBaseText(): String =
        if (lastClickedInfo.isEmpty()) "Terminal" else "Terminal | $lastClickedInfo"

    fun cancelTemp() {
        terminalTempJob?.cancel()
        terminalTempJob = null
        terminalTempActive = true
    }

    fun restoreTerminalStatus() {
        terminalTempJob?.cancel()
        terminalTempJob = null
        terminalTempActive = false
        lastClickedInfo = ""
        if (activity.currentTab == 0 && ::statusView.isInitialized) apply(terminalBaseText())
    }

    fun showTerminalTemp(text: String, durationMs: Long) {
        terminalTempJob?.cancel()
        terminalTempActive = true
        setStatusTextAnimated(text)
        terminalTempJob = activity.lifecycleScope.launch {
            delay(durationMs)
            terminalTempActive = false
            lastClickedInfo = ""
            if (activity.currentTab == 0 && ::statusView.isInitialized) apply(terminalBaseText())
        }
    }

    fun onCardUsed(label: String, state: String, count: Int) {
        lastClickedInfo = "$state : $label <$count>"
        showTerminalTemp("Terminal | $lastClickedInfo", 2000L)
    }

    fun setStatusTextAnimated(text: String) {
        if (!::statusView.isInitialized) return
        statusView.animate().cancel()
        statusView.animate().alpha(0f).setDuration(120).withEndAction {
            apply(text)
            statusView.animate().alpha(1f).setDuration(120).start()
        }.start()
    }

    fun setStatusText(text: String) {
        if (::statusView.isInitialized) apply(text)
    }

    /**
     * 5.9.47：**唯一**的赋值入口 —— 把一整条文字拆进两块。
     *
     * ```
     * "Files | /workspace"  ->  块① "Files"   块② "| /workspace"
     * "Terminal"             ->  块① "Terminal"  块② ""
     * "已保存"（临时提示）      ->  块① 不动      块② "已保存"
     * ```
     *
     * 临时提示**不动块①**：它是"事件"，不是"当前位置"。
     * 竖线归块② —— 用户要求特征字只放词、不要竖线；竖线仍留在内容里，
     * 作为"这是内容区"的视觉起点。
     */
    private fun apply(text: String) {
        val name = StatusAccent.tabNameOf(text)
        if (name != null) tabLabel.text = name
        statusView.text = StatusAccent.contentOf(text)
    }

    // ---------- 浏览器的闪烁提示 ----------

    private var webTick: Runnable? = null
    private var webPhase: Boolean? = null
    private var webSince = 0L
    private var webIdleCheck: Runnable? = null

    /**
     * 让 status 栏与"闲置提示"对齐（切 Tab、开关服务、onResume、每条指令后都会调）。
     *
     * 5.9.1 起改成**闲置满 10 分钟才提示**（此前是"开着没用过就闪"，用着开着也一直闪）。
     * 心跳只在**真的要闪**的时候才排；等待期只排**一个一次性延时检查**，到点若仍空闲才开始闪——
     * 所以空闲的那十分钟里完全不空转。
     *
     * 避让规则（临时提示 > 按 Ctrl 的按键信息 > 闪烁）全交给 [WebNoticeArbiter] 判定，
     * 规则本身有单测锁着。
     */
    fun syncWebNotice() {
        if (activity.isFinishing || activity.isDestroyed) return
        val inTerminal = activity.currentTab == 0
        val serviceOn = WebAutomationService.isRunning
        val idleMs = WebAutomationService.idleMillis()
        val want = inTerminal && serviceOn && idleMs >= WebNoticeArbiter.IDLE_MS

        webIdleCheck?.let { scope.mainHandler.removeCallbacks(it) }
        webIdleCheck = null

        val running = webTick
        if (want && running == null) {
            webSince = SystemClock.elapsedRealtime()
            webPhase = null
            val r = object : Runnable {
                override fun run() {
                    renderWebNotice()
                    if (webTick === this) {
                        scope.mainHandler.postDelayed(this, WebNoticeArbiter.TICK_MS)
                    }
                }
            }
            webTick = r
            scope.mainHandler.post(r)
            return
        }
        if (!want) {
            if (running != null) {
                scope.mainHandler.removeCallbacks(running)
                webTick = null
                webIdleCheck?.let { scope.mainHandler.removeCallbacks(it) }
                webIdleCheck = null
                webPhase = null
                if (inTerminal && ::statusView.isInitialized) {
                    statusView.animate().cancel()
                    apply(terminalBaseText())
                }
            }
            // 还没闲置够：排一个到点的检查，届时若仍空闲才开始闪
            if (inTerminal && serviceOn) {
                val delay = WebNoticeArbiter.msUntilBlink(idleMs)
                if (delay in 1..MAX_IDLE_CHECK_DELAY_MS) {
                    val probe = Runnable { syncWebNotice() }
                    webIdleCheck = probe
                    scope.mainHandler.postDelayed(probe, delay)
                }
            }
            return
        }
        // 已经在闪：相位或避让状态变了就重画
        renderWebNotice()
    }

    /** 只在相位变化时改文本，且临时提示期间一律不碰 status（否则会把 2 秒提示闪没）。 */
    private fun renderWebNotice() {
        if (!::statusView.isInitialized) return
        val inputs = webNoticeInputs()
        if (inputs.tempStatusActive) return
        val on = WebNoticeArbiter.noticeVisible(inputs, SystemClock.elapsedRealtime() - webSince)
        if (on == webPhase) return
        webPhase = on
        statusView.animate().cancel()
        apply(if (on) {
            "Terminal | " + activity.getString(R.string.web_notice_blink)
        } else {
            terminalBaseText()
        })
    }

    private fun webNoticeInputs() = WebNoticeArbiter.Inputs(
        serviceOn = WebAutomationService.isRunning,
        idleMs = WebAutomationService.idleMillis(),
        tempStatusActive = terminalTempActive,
        ctrlInfoVisible = lastClickedInfo.isNotEmpty()
    )

    /** 有按键信息或临时提示时，先让它们说话（顺带把闪烁收掉）。 */
    fun yieldWebNotice() {
        renderWebNotice()
    }

    /** 终端 Tab 不打扰；其余 Tab 展示 2 秒临时提示。 */
    fun showTempStatus(msg: String) {
        if (activity.currentTab == 0) return
        statusGen++
        setStatusTextAnimated(msg)
        settingsStatusJob?.let { scope.mainHandler.removeCallbacks(it) }
        val r = Runnable {
            statusGen++
            activity.refreshStatusBar()
            settingsStatusJob = null
        }
        settingsStatusJob = r
        scope.mainHandler.postDelayed(r, 2000)
    }

    fun showPreviousCrash(rootLayout: FrameLayout) {
        runCatching {
            val crashFile = File(activity.filesDir, "crash.log")
            if (!crashFile.exists()) return
            val seenKey = "crashToast_${crashFile.lastModified()}"
            if (scope.prefs.getBoolean(seenKey, false)) return
            scope.prefs.edit().putBoolean(seenKey, true).apply()
            val text = runCatching { crashFile.readText() }.getOrDefault("")
            val cause = (activity.application as TermLouApp).rootCauseLine(text)
            val show = {
                Snackbar.make(
                    rootLayout,
                    activity.getString(R.string.crash_snack_fmt, cause.take(160)),
                    Snackbar.LENGTH_LONG
                ).show()
            }
            if (rootLayout.isAttachedToWindow) {
                show()
            } else {
                rootLayout.post { if (rootLayout.isAttachedToWindow) show() }
            }
            if (text.isNotBlank()) {
                runCatching {
                    val clip = android.content.ClipData.newPlainText("TermLou crash", text)
                    (activity.getSystemService(android.content.ClipboardManager::class.java)).setPrimaryClip(clip)
                }
            }
        }
    }

    fun onDestroy() {
        terminalTempJob?.cancel()
        terminalTempJob = null
        settingsStatusJob?.let { scope.mainHandler.removeCallbacks(it) }
        settingsStatusJob = null
        webTick?.let { scope.mainHandler.removeCallbacks(it) }
        webTick = null
        webIdleCheck?.let { scope.mainHandler.removeCallbacks(it) }
        webIdleCheck = null
    }

    private companion object {
        /** 一次性空闲检查的最长延时；超过就不排了，等下次刷新 status 时再算。 */
        const val MAX_IDLE_CHECK_DELAY_MS = 10 * 60 * 1000L
    }
}
