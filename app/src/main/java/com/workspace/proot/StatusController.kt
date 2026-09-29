package com.workspace.proot

import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
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

    private var statusGen = 0
    private var lastClickedInfo = ""
    private var terminalTempActive = false
    private var terminalTempJob: Job? = null
    private var settingsStatusJob: Runnable? = null

    fun createStatusBar(): TextView {
        statusView = scope.uiBuilder.createStatusBar()
        return statusView
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
        if (activity.currentTab == 0 && ::statusView.isInitialized) statusView.text = terminalBaseText()
    }

    fun showTerminalTemp(text: String, durationMs: Long) {
        terminalTempJob?.cancel()
        terminalTempActive = true
        setStatusTextAnimated(text)
        terminalTempJob = activity.lifecycleScope.launch {
            delay(durationMs)
            terminalTempActive = false
            lastClickedInfo = ""
            if (activity.currentTab == 0 && ::statusView.isInitialized) statusView.text = terminalBaseText()
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
            statusView.text = text
            statusView.animate().alpha(1f).setDuration(120).start()
        }.start()
    }

    fun setStatusText(text: String) {
        if (::statusView.isInitialized) statusView.text = text
    }

    // ---------- 无头浏览器的闪烁提示 ----------

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
                    statusView.text = terminalBaseText()
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
        statusView.text = if (on) {
            "Terminal | " + activity.getString(R.string.web_notice_blink)
        } else {
            terminalBaseText()
        }
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
