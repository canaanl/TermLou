package com.workspace.proot

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

    /** 浠呯綉缁?璁剧疆 Tab 灞曠ず鐨?2 绉掍复鏃舵彁绀猴紙缁堢/鏂囦欢 Tab 涓嶆墦鎵帮級銆?*/
    fun showTempStatus(msg: String) {
        if (activity.currentTab != 2 && activity.currentTab != 3) return
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
    }
}
