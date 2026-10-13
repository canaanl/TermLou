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

    /**
     * 5.9.47：块① 特征字（Terminal/Notes/Files/Network/Settings），固定宽度 + 特征色。
     *
     * 5.9.48：从 `TextView` 换成 [LaserTabLabelView] —— 换页签时旧词被白色激光扫掉、
     * 露出新词（激光打标效果）。两个词**都左对齐、永不平移**。
     */
    private lateinit var tabLabel: LaserTabLabelView

    /**
     * 5.9.48：两块共用的上下内距（px）。
     *
     * ## 这个数是怎么来的（不是凑整）
     *
     * 状态栏字号 13sp（`TEXT_COMPACT`）。Roboto 在此字号下 ascent+descent ≈ **15.2dp**，
     * 而 `g`/`y` 的下半截（descent）**已经包含在那 15.2dp 里**。
     *
     * 所以内距**不是**"给下摆字母留的地方" —— 它只是"别让字形贴到条子边缘"的一点点余量。
     * 2dp 足够；**4dp 就是白占高度**（用户实机反馈"上下 4dp 也太大"）。
     *
     * 另外安卓默认的"字体留白"还会再占约 3.7dp，那个对 descent 毫无用处，
     * 已在两块上都关掉（`includeFontPadding = false`）。
     *
     * 实测条高对比：原 8+0（含字体留白）≈ 27dp → 本版 2+2（无字体留白）≈ **19dp**。
     *
     * ⚠ 这个值**不在 MD3 的 4dp 网格上**，是有意为之：它是从字体度量推出来的排版余量，
     * 不是"间距档位"。网格管的是元素之间的距离，管不到字形自身的呼吸。
     */
    private val STATUS_V_PAD: Int by lazy { activity.dp(2) }

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

        // 5.9.48：块① 从普通 TextView 换成**自绘控件**，为了做激光打标的切换动画。
        // 它自己保证"两个词都左对齐、只有白光在动"（见 LaserTabLabelView 的 KDoc）。
        tabLabel = LaserTabLabelView(activity).apply {
            setAccent(theme.statusAccent)
            // 上下内距必须**对称**（一边 8 一边 0 不叫内距，叫偏心）。
            // 左内距 16dp 让特征字离屏幕边缘有呼吸；右侧不留内距 ——
            // 块① 的**宽度**里已经含了到块② 的间隔（见 measureTabWidth）。
            setPadding(activity.dp(UiTokens.SPACE_4), STATUS_V_PAD, 0, STATUS_V_PAD)
            // 宽度由 measureTabWidth() 定死（五个词里最宽的），换页签不跳
            layoutParams = LinearLayout.LayoutParams(measureTabWidth(), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        createStatusBar()
        // 5.9.48：块② 的内距在这里下发（不在 UiBuilder 里设）——
        // **两块必须用同一个垂直内距**，那只该有一个出处，就是组装状态栏的这里。
        statusView.setPadding(
            activity.dp(UiTokens.SPACE_4), STATUS_V_PAD,
            activity.dp(UiTokens.SPACE_4), STATUS_V_PAD,
        )
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
     * 5.9.48：文件页的**路径**入口 —— 与 [setStatusText] 的区别只有一个但很关键：
     * **路径超长时立刻开横向跑马灯**。
     *
     * ## 为什么需要单独一个入口
     *
     * 此前开启跑马灯的代码只在**点过文件/文件夹之后**的收尾动作里（`StatusMarquee.show` 的
     * onDone → `restoreStatusPath`）。于是：
     *
     * | 操作 | 旧行为 |
     * |---|---|
     * | 点进一个很深的文件夹 | ✅ 会滚（收尾动作开的） |
     * | **切到文件页** | ❌ 不滚（没人开） |
     * | **切走再切回来** | ❌ 不滚（没人开） |
     *
     * 用户明确要求"只要是路径长于显示就要一直滚动" —— 所以切到文件页这一下
     * 就该把跑马灯开起来，不必等用户去点东西。
     */
    fun setFilesPath(path: String) {
        if (!::statusView.isInitialized) return
        tabLabel.setTabName("Files", animate = false, toLeft = tabSweepLeft)
        if (path.isEmpty()) {
            StatusMarquee.clearPersistent(statusView)
            statusView.text = ""
            return
        }
        StatusMarquee.showPersistent(statusView, path)
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
        if (name != null) {
            // 只有**真的换词**才放动画；同一页签内反复刷新（临时提示、网络状态行等）
            // 传进来的名字一样，此时 setTabName 内部会走"不动画"分支，不会闪。
            tabLabel.setTabName(name, animate = true, toLeft = tabSweepLeft)
        }
        statusView.text = StatusAccent.contentOf(text)
    }

    /**
     * 5.9.48：激光扫动方向 = 页面滑动方向。
     *
     * 切到**后面的**页签时内容向左滑、新页面从右边进来 → 激光也**从右往左**扫
     * （新词先从右侧出现）；切到前面的页签反之。
     *
     * 由 `MainActivity.showTab` 在切换前告知 —— 它才知道 `tabIndex > displayedTab`。
     */
    private var tabSweepLeft = true

    /** 见 [tabSweepLeft]。 */
    fun setSweepDirection(toLeft: Boolean) {
        tabSweepLeft = toLeft
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
