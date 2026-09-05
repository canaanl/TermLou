package com.workspace.proot

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import android.animation.ValueAnimator
import android.os.Build
import android.view.animation.DecelerateInterpolator

/**
 * 缁堢鍩燂細缁堢瑙嗗浘/鎷ㄨ疆/蹇嵎閿銆乻hell 鍚姩锛堝惈寮€灞忥級銆乮nstallRootfs銆? * 瀛楀彿/ctrl 閿€乀erminalSessionClient/ViewClient 瀹炵幇銆? * 鍘?MainActivity 缁堢鐩稿叧 ~550 琛屾敹褰掓澶勶紱Activity 浠呬繚鐣?showTab 澹炽€? */
class TerminalController(
    private val activity: MainActivity,
    private val scope: AppScope,
    private val status: StatusController,
    private val lifecycle: LifecycleCoroutineScope,
    private val onOpenShortcutSettings: () -> Unit
) : TerminalSessionClient, TerminalViewClient {

    internal lateinit var terminalView: TerminalView
    internal var wheelController: WheelController? = null

    private lateinit var wheelPanel: FrameLayout
    private lateinit var wheelRecycler: RecyclerView
    private lateinit var upperWheelPanel: FrameLayout
    private lateinit var upperWheelRecycler: RecyclerView
    private lateinit var shortcutContainer: SwipeableContainer
    private lateinit var shortcutInner: LinearLayout
    private lateinit var columnsWrapper: LinearLayout
    private lateinit var rowTop: LinearLayout
    private lateinit var rowBottom: LinearLayout
    private var wheelCardH = 0

    private lateinit var setupArea: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private var ctrlButton: Button? = null
    private var ctrlArmJob: Job? = null
    private var fontSliderAnimator: ValueAnimator? = null

    var fontSizeIndex = scope.settingsManager.fontSizeIndex
        private set
    var fontSizeSp = scope.settingsManager.fontSizeSp
        private set

    private var splashStartTime = 0L
    private var touchStartX = 0f
    private val swipeThreshold by lazy { (50 * activity.resources.displayMetrics.density).toInt() }

    fun initFontFromSettings() {
        fontSizeIndex = scope.settingsManager.fontSizeIndex
        fontSizeSp = scope.settingsManager.fontSizeSp
    }

    fun fontNames(): List<String> =
        activity.resources.getStringArray(R.array.font_size_names).toList()

    /** 缁堢 Tab 鏁村潡 UI锛堝惈鎷ㄨ疆涓庡揩鎹烽敭琛岋級锛屾寕鍒?terminalArea 涓嬨€?*/
    fun buildInto(terminalArea: LinearLayout) {
        val density = activity.resources.displayMetrics.density
        wheelCardH = (52 * density).toInt()

        terminalView = TerminalView(activity, null).apply {
            setTerminalViewClient(this@TerminalController)
            setTextSize(fontSizeSp)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFocusableInTouchMode = true
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    touchStartX = event.x
                } else if (event.action == MotionEvent.ACTION_UP) {
                    val dx = event.x - touchStartX
                    if (dx < -swipeThreshold && activity.currentTab == 0 && setupArea.visibility != View.VISIBLE) {
                        activity.showFilesView()
                    }
                }
                false
            }
        }

        wheelRecycler = NonFlingRecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        wheelPanel = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, wheelCardH,
                Gravity.BOTTOM
            )
            setBackgroundColor(Color.parseColor("#B31E1E1E"))
            visibility = View.GONE
            addView(wheelRecycler)
        }

        upperWheelPanel = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, wheelCardH,
                Gravity.BOTTOM
            ).apply { bottomMargin = wheelCardH }
            setBackgroundColor(Color.parseColor("#B31E1E1E"))
            visibility = View.GONE
        }
        upperWheelRecycler = NonFlingRecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        upperWheelPanel.addView(upperWheelRecycler)

        wheelController = WheelController(
            activity, activity.lifecycleScope, scope.shortcutManager,
            wheelPanel, wheelRecycler, upperWheelPanel, upperWheelRecycler,
            wheelCardH,
            onStatusRestore = { status.restoreTerminalStatus() }
        )

        val terminalWrapper = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(terminalView)
            addView(upperWheelPanel)
            addView(wheelPanel)
        }
        terminalArea.addView(terminalWrapper)

        rowTop = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        rowBottom = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        columnsWrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(rowTop)
            addView(rowBottom)
        }
        shortcutInner = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(scope.cSurface)
            addView(columnsWrapper)
        }
        shortcutContainer = SwipeableContainer(activity)
        shortcutContainer.addView(shortcutInner)

        terminalArea.addView(shortcutContainer)

        shortcutInner.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val h = rowTop.height
            if (h <= 0) return@addOnLayoutChangeListener
            val target = h + (8 * density).toInt()
            if (target != wheelCardH) {
                wheelCardH = target
                val lp = wheelPanel.layoutParams as? FrameLayout.LayoutParams ?: return@addOnLayoutChangeListener
                lp.height = wheelCardH
                wheelPanel.layoutParams = lp
                val ulp = upperWheelPanel.layoutParams as? FrameLayout.LayoutParams ?: return@addOnLayoutChangeListener
                ulp.height = wheelCardH
                ulp.bottomMargin = wheelCardH
                upperWheelPanel.layoutParams = ulp
                wheelController?.updateCardHeight(wheelCardH)
            }
        }

        shortcutContainer.onSwipeLeft = { wheelController?.toggle() }
        shortcutContainer.onLongSwipeLeft = { onOpenShortcutSettings() }
        shortcutContainer.onClickPassthrough = { ev ->
            performClickAt(shortcutContainer, ev.rawX, ev.rawY)
        }

        refreshAllRows()
    }

    /** 寮€灞忓畨瑁呭尯锛坰etupArea + 杩涘害鏉★級锛岀敱 Activity 鎸傚埌 content銆?*/
    fun buildSetupArea(): LinearLayout {
        val views = scope.uiBuilder.createSetupArea(
            onInstallClick = { btn -> installRootfs(btn) }
        )
        setupArea = views.area
        progressBar = views.progressBar
        progressText = views.progressText
        return setupArea
    }

    /** 寮€鏈哄垎鏀細rootfs 灏辩华鍒?splash + startShell锛屽惁鍒欎寒 setupArea銆?*/
    fun boot(rootLayout: FrameLayout, tileCommand: String?) {
        if (File(scope.lxRoot, "etc/passwd").exists()) {
            setupArea.visibility = View.GONE
            scope.fromTile = tileCommand != null
            val splash = SplashView(activity, loadSplashCells())
            rootLayout.addView(splash)
            splash.bringToFront()
            splashStartTime = System.currentTimeMillis()
            startShell(splash)
        } else {
            setupArea.visibility = View.VISIBLE
        }
    }

    fun refreshAllRows() {
        scope.shortcutManager.refreshAllRows(rowTop, rowBottom, shortcutInner, ::createShortcutKey, ::createCtrlKey)
        wheelController?.refreshAll()
    }

    private fun performClickAt(parent: ViewGroup, rawX: Float, rawY: Float) {
        val loc = IntArray(2)
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            child.getLocationOnScreen(loc)
            val left = loc[0]
            val top = loc[1]
            val right = left + child.width
            val bottom = top + child.height
            if (rawX >= left && rawX < right && rawY >= top && rawY < bottom) {
                when (child) {
                    is Button -> { child.performClick(); return }
                    is ViewGroup -> { performClickAt(child, rawX, rawY); return }
                }
            }
        }
    }

    private fun createShortcutKey(label: String, seq: String, hasCtrl: Boolean = false, ctrlSeq: String = "", widthPx: Int = 0): Button {
        return scope.uiBuilder.createShortcutKey(label, seq, hasCtrl, ctrlSeq, widthPx) { s, h, c ->
            val armed = scope.terminalManager.ctrlMode
            val finalSeq = if (armed && h) c else s
            if (armed) {
                setCtrlArmed(false, if (h) null else "Ctrl OFF", if (h) 0 else 1000L)
            }
            activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val bytes = finalSeq.toByteArray()
                    scope.terminalManager.session?.write(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    Log.e("shortcut", "write failed", e)
                }
            }
        }
    }

    private fun createCtrlKey(widthPx: Int = 0): Button {
        val btn = scope.uiBuilder.createCtrlKey(widthPx) {
            if (scope.terminalManager.ctrlMode) {
                setCtrlArmed(false, "Ctrl OFF", 1000L)
            } else {
                setCtrlArmed(true, null, 0)
            }
        }
        ctrlButton = btn
        return btn
    }

    private fun updateCtrlButtonColor() {
        val color = if (scope.terminalManager.ctrlMode) scope.cError else scope.cOutline
        ctrlButton?.let { ButtonStyle.apply(it, color) }
    }

    private fun setCtrlArmed(armed: Boolean, feedback: String?, feedbackMs: Long) {
        ctrlArmJob?.cancel()
        ctrlArmJob = null
        scope.terminalManager.setCtrlMode(armed)
        updateCtrlButtonColor()
        if (armed) {
            status.cancelTemp()
            status.setStatusTextAnimated("Ctrl ON (next key)")
            ctrlArmJob = activity.lifecycleScope.launch {
                delay(3000)
                if (scope.terminalManager.ctrlMode) {
                    scope.terminalManager.setCtrlMode(false)
                    updateCtrlButtonColor()
                    status.showTerminalTemp(activity.getString(R.string.ctrl_reset), 1000L)
                } else {
                    status.restoreTerminalStatus()
                }
            }
        } else if (feedback != null) {
            status.showTerminalTemp(feedback, feedbackMs)
        } else {
            status.restoreTerminalStatus()
        }
    }

    fun applyFontSettings() {
        terminalView.setTextSize(fontSizeSp)
        status.showTempStatus(activity.getString(R.string.font_changed_fmt, fontNames()[fontSizeIndex]))
    }

    fun bindFontSlider(slider: Slider) {
        attachFontSliderAnimation(slider)
    }

    internal fun isSetupVisible(): Boolean =
        ::setupArea.isInitialized && setupArea.visibility == View.VISIBLE

    internal fun hideSetup() {
        if (::setupArea.isInitialized) setupArea.visibility = View.GONE
    }

    private fun attachFontSliderAnimation(slider: Slider) {
        val maxStep = (slider.valueTo - slider.valueFrom).toInt()
        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        var downX = 0f
        val stepFor: (Float) -> Float = { x ->
            val frac = (x / slider.width).coerceIn(0f, 1f)
            (frac * maxStep).roundToInt().coerceIn(fontNames().indices).toFloat()
        }
        val continuousFor: (Float) -> Float = { x ->
            val frac = (x / slider.width).coerceIn(0f, 1f)
            slider.valueFrom + frac * (slider.valueTo - slider.valueFrom)
        }
        slider.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fontSliderAnimator?.cancel()
                    downX = event.x
                    animateSliderStep(slider, stepFor(event.x))
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > touchSlop) {
                        fontSliderAnimator?.cancel()
                        slider.value = continuousFor(event.x)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    animateSliderStep(slider, stepFor(event.x))
                    applyFontSizeStep(stepFor(event.x))
                    v.performClick()
                }
            }
            true
        }
    }

    private fun animateSliderStep(slider: Slider, target: Float) {
        fontSliderAnimator?.cancel()
        if (slider.value == target) return
        fontSliderAnimator = ValueAnimator.ofFloat(slider.value, target).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                slider.value = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun applyFontSizeStep(index: Float) {
        val idx = index.roundToInt().coerceIn(fontNames().indices)
        fontSizeIndex = idx
        fontSizeSp = scope.settingsManager.fontSizes[idx]
        scope.settingsManager.setFontSizeIndex(idx)
        applyFontSettings()
    }

    fun installRootfs(setupBtn: Button) {
        setupBtn.isEnabled = false
        setupBtn.text = activity.getString(R.string.setup_extracting)
        progressBar.isIndeterminate = true
        progressText.text = activity.getString(R.string.setup_extract_rootfs)

        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RootfsExtractor(activity).extractTo(scope.lxRoot)
                }
                progressText.text = "RootFS ready!"
                progressBar.isIndeterminate = false
                progressBar.progress = 100
                setupArea.visibility = View.GONE
                val splash = SplashView(activity)
                activity.rootLayout.addView(splash)
                splash.bringToFront()
                startShell(splash)
            } catch (e: Exception) {
                Log.e("RootfsExtractor", "extractTo failed", e)
                progressText.text = "Error: ${e.javaClass.simpleName}: ${e.message}"
                setupBtn.isEnabled = true
                setupBtn.text = activity.getString(R.string.retry)
            }
        }
    }

    /** 寮€鏈?閲嶈鍏辩敤鐨?shell 鍚姩锛氱幆澧冨噯澶囨敹褰?TerminalManager锛宻plash 涓庣璐村洖濉暀鍦ㄦ銆?*/
    fun startShell(splash: SplashView) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                setSplashStatus(splash, activity.getString(R.string.splash_preparing))
                val tm = scope.terminalManager
                val effectiveCmd = if (scope.fromTile) "" else scope.settingsManager.shellCmd
                tm.setupEnvironment(effectiveCmd)

                // Migrate old workspace/files/ 鈫?workspace/
                val oldWsFiles = File(activity.filesDir, "workspace/files")
                if (oldWsFiles.exists() && oldWsFiles.isDirectory) {
                    oldWsFiles.listFiles()?.forEach { f ->
                        val dest = File(scope.wsFiles, f.name)
                        if (!dest.exists()) f.renameTo(dest)
                    }
                    oldWsFiles.delete()
                }

                tm.syncDnsToRootfs()
                setSplashStatus(splash, activity.getString(R.string.splash_deps))
                val curlBin = File(scope.lxRoot, "usr/bin/curl")
                if (!curlBin.exists() || !File(scope.lxRoot, "etc/ssl/certs/ca-certificates.crt").exists()) {
                    val offlineCopy = runCatching {
                        if (!curlBin.exists()) {
                            activity.assets.open("curl_aarch64").use { input ->
                                curlBin.parentFile?.mkdirs()
                                curlBin.outputStream().use { input.copyTo(it) }
                            }
                            curlBin.setExecutable(true, false)
                        }
                        val caTarget = File(scope.lxRoot, "etc/ssl/certs/ca-certificates.crt")
                        if (!caTarget.exists()) {
                            activity.assets.open("cacert.pem").use { input ->
                                caTarget.parentFile?.mkdirs()
                                caTarget.outputStream().use { input.copyTo(it) }
                            }
                        }
                        curlBin.exists()
                    }.getOrDefault(false)
                    if (!offlineCopy) {
                        tm.runInProot("dpkg --configure -a 2>/dev/null; apt-get update -qq 2>/dev/null; apt-get install -y -qq curl ca-certificates tar 2>/dev/null; update-ca-certificates -f 2>/dev/null", 300)
                    }
                }
                val prootBin = File(activity.applicationInfo.nativeLibraryDir, "libproot_exec.so")
                val loader = File(activity.applicationInfo.nativeLibraryDir, "libproot_loader.so")

                setSplashStatus(splash, activity.getString(R.string.splash_starting))
                val shellPath = tm.findShellInRootfs()
                if (shellPath == null) {
                    withContext(Dispatchers.Main) { status.setStatusText(activity.getString(R.string.shell_not_found)) }
                    return@launch
                }

                val args = tm.buildProotArgs(shellPath)
                val env = tm.buildProotEnv(loader)

                withContext(Dispatchers.Main) {
                    val newSession = TerminalSession(
                        prootBin.absolutePath,
                        scope.wsFiles.absolutePath,
                        args.toTypedArray(),
                        env,
                        null,
                        this@TerminalController
                    )
                    tm.setSession(newSession)
                    terminalView.attachSession(newSession)
                    activity.showTerminalView()
                    val remaining = maxOf(0L, 800L - (System.currentTimeMillis() - splashStartTime))
                    val tileAction = Runnable {
                        scope.pendingTileCommand?.let { cmd ->
                            scope.pendingTileCommand = null
                            val data = (cmd + "\n").toByteArray()
                            newSession.write(data, 0, data.size)
                        }
                    }
                    if (remaining > 0) {
                        splash.postDelayed({
                            splash.dismiss { activity.rootLayout.removeView(splash) }
                            splash.postDelayed(tileAction, 200)
                        }, remaining)
                    } else {
                        splash.dismiss { activity.rootLayout.removeView(splash) }
                        splash.postDelayed(tileAction, 200)
                    }
                }
            } catch (e: Exception) {
                Log.e("startShell", "shell start failed", e)
                withContext(Dispatchers.Main) {
                    activity.rootLayout.removeView(splash)
                    status.setStatusText(activity.getString(R.string.shell_error_fmt, e.message.toString()))
                }
            }
        }
    }

    private fun setSplashStatus(splash: SplashView, text: String) {
        splash.post {
            splash.statusText = text
            splash.invalidate()
        }
    }

    private fun loadSplashCells(): List<Pair<Int, Int>>? {
        runCatching {
            val f = TermlouDirs.base(activity)
            val splashJson = File(f, "splash.json")
            if (!splashJson.exists()) return null
            val obj = MiniJson.parse(splashJson.readText())
            val arr = obj.optArr("cells") ?: return null
            val out = mutableListOf<Pair<Int, Int>>()
            for (i in 0 until arr.length()) {
                val o = arr.getObj(i) ?: continue
                val r = o.optInt("r", -1)
                val c = o.optInt("c", -1)
                if (r in 0 until SplashTokens.ROWS && c in 0 until SplashTokens.COLS) out.add(r to c)
            }
            return if (out.isEmpty()) null else out
        }
        return null
    }

    private fun isSymlink(file: File) = java.nio.file.Files.isSymbolicLink(file.toPath())

    fun onResume() {
        if (wheelPanel.visibility == View.VISIBLE) wheelController?.onResume()
    }

    fun onDestroy() {
        ctrlArmJob?.cancel()
        ctrlArmJob = null
        fontSliderAnimator?.cancel()
        fontSliderAnimator = null
        scope.terminalManager.setSession(null)
        scope.terminalManager.destroy()
    }

    // ---------- TerminalSessionClient ----------

    override fun onTextChanged(changedSession: TerminalSession?) {
        terminalView.onScreenUpdated()
        if (scope.settingsManager.keepAlive) KeepAliveWakeLock.poke()
    }

    override fun onTitleChanged(changedSession: TerminalSession?) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession?) = Unit

    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
        val clip = android.content.ClipData.newPlainText("terminal", text)
        (activity.getSystemService(android.content.ClipboardManager::class.java)).setPrimaryClip(clip)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = (activity.getSystemService(android.content.ClipboardManager::class.java)).primaryClip
        val text = clip?.getItemAt(0)?.text?.toString() ?: return
        session?.write(text.toByteArray(), 0, text.length)
    }

    override fun onBell(session: TerminalSession?) = Unit
    override fun onColorsChanged(session: TerminalSession?) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { android.util.Log.e(tag ?: "Terminal", message ?: "") }
    override fun logWarn(tag: String?, message: String?) { android.util.Log.w(tag ?: "Terminal", message ?: "") }
    override fun logInfo(tag: String?, message: String?) { android.util.Log.i(tag ?: "Terminal", message ?: "") }
    override fun logDebug(tag: String?, message: String?) { android.util.Log.d(tag ?: "Terminal", message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { android.util.Log.v(tag ?: "Terminal", message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { android.util.Log.e(tag ?: "Terminal", message ?: "", e) }
    override fun logStackTrace(tag: String?, e: Exception?) { android.util.Log.e(tag ?: "Terminal", "", e) }

    // ---------- TerminalViewClient ----------

    override fun onScale(scale: Float): Float = scale.coerceIn(0.5f, 2.0f)
    override fun onSingleTapUp(e: MotionEvent?) {
        val imm = activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        val keyboardVisible = Build.VERSION.SDK_INT >= 30 && activity.window.decorView.rootWindowInsets
            ?.isVisible(android.view.WindowInsets.Type.ime()) == true
        if (keyboardVisible) {
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
        } else {
            terminalView.requestFocus()
            terminalView.post { imm.showSoftInput(terminalView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
        }
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = true
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        if (scope.terminalManager.handleCtrlKey(codePoint)) {
            setCtrlArmed(false, null, 0)
            return true
        }
        return false
    }
    override fun onEmulatorSet() = Unit
}
