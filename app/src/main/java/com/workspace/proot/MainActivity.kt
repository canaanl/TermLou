package com.workspace.proot

import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Uri
import android.net.VpnService
import android.service.quicksettings.TileService
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import androidx.lifecycle.lifecycleScope
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var rootLayout: FrameLayout
    private lateinit var statusText: TextView
    private var lastClickedInfo = ""
    private var terminalTempActive = false
    private var terminalTempJob: Job? = null
    private var ctrlArmJob: Job? = null
    private lateinit var setupArea: LinearLayout
    private lateinit var terminalArea: LinearLayout
    private lateinit var filesArea: LinearLayout
    private lateinit var filesHeader: TextView
    private lateinit var fileList: LinearLayout
    private var filesListRoot: FrameLayout? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var terminalView: TerminalView
    private lateinit var terminalTab: TextView
    private lateinit var filesTab: TextView
    private lateinit var networkTab: TextView
    private lateinit var settingsTab: TextView
    private var touchStartX = 0f
    private val swipeThreshold by lazy { (50 * resources.displayMetrics.density).toInt() }
    private var animating = false
    private companion object {
        const val DIM = 0f
    }
    private lateinit var slideContainer: FrameLayout
    private lateinit var tabHost: FrameLayout
    private lateinit var tabIndicator: View


    private val lxRoot get() = File(this.filesDir, "workspace/linux")
    private val wsFiles get() = File(this.filesDir, "workspace")
    private val wsTmp get() = File(this.filesDir, "workspace/tmp")

    private var settingsStatusJob: Runnable? = null
    private var ctrlButton: Button? = null
    private var currentTab = 0
    private var statusGen = 0
    private var quickInitArmed = false
    private var quickInitArmedGen = -1
    private var fontSizeIndex = 2
    private var fontSizeSp = 28
    private var shellCmd = ""
    private var tileCommand = ""
    private var keepAlive = false
    private var suppressSwitch = false
    private var fontSliderAnimator: ValueAnimator? = null
    private var batteryOptPending = false
    private var splashStartTime = 0L
    private var fromTile = false
    private var pendingTileCommand: String? = null
    private val prefs by lazy { getSharedPreferences("term-lou-settings", MODE_PRIVATE) }
    private val fontSizes = listOf(18, 22, 28, 34, 40)
    private fun fontNames(): List<String> = resources.getStringArray(R.array.font_size_names).toList()
    private lateinit var shortcutContainer: SwipeableContainer
    private lateinit var shortcutInner: LinearLayout
    private lateinit var columnsWrapper: LinearLayout
    private lateinit var rowTop: LinearLayout
    private lateinit var rowBottom: LinearLayout
    private lateinit var wheelPanel: FrameLayout
    private lateinit var wheelRecycler: RecyclerView
    private lateinit var upperWheelPanel: FrameLayout
    private lateinit var upperWheelRecycler: RecyclerView
    private var wheelController: WheelController? = null
    private var wheelCardH = 0
    private var needRefreshShortcuts = false
    private lateinit var settingsInner: LinearLayout
    private lateinit var settingsWrapper: LinearLayout
    private lateinit var networkArea: LinearLayout
    private lateinit var networkInner: LinearLayout
    private lateinit var netToggleBtn: Button
    private lateinit var netMenuHost: FrameLayout
    private lateinit var netBraceMenu: BraceMenu
    private lateinit var netFlowList: LinearLayout
    private lateinit var lanToggleBtn: Button
    private lateinit var lanAuthBtn: Button
    private lateinit var lanStatusText: TextView
    private lateinit var lanUrlText: TextView
    private val netExpandedIds = HashSet<Long>()
    private var netFlowDirty = false
    private var lastNetStatusShown: String? = null
    private var netFlowListener: (() -> Unit)? = null
    private var netRulesListener: (() -> Unit)? = null
    private val netFlowRunnable = Runnable {
        if (netFlowDirty && currentTab == 2) {
            netFlowDirty = false
            renderNetFlows()
        }
    }

    private lateinit var theme: ThemeColors
    private lateinit var settingsManager: SettingsManager
    private var cachedApps: List<AppEntry>? = null
    private lateinit var terminalManager: TerminalManager
    private lateinit var uiBuilder: UiBuilder
    private lateinit var shortcutManager: ShortcutManager
    private lateinit var fileListManager: FileListManager
    private var cSurface = 0
    private var cSurfaceVariant = 0
    private var cPrimaryContainer = 0
    private var cOutline = 0
    private var cOnSurface = 0
    private var cOnSurfaceVariant = 0
    private var cPrimary = 0
    private var cError = 0
    private var cTertiary = 0

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        for (uri in uris) importFile(uri)
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val importFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { syncFolderFromUri(it) } }

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            NetVpnService.start(this)
        } else {
            showTempStatus(getString(R.string.vpn_auth_cancelled))
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        suppressSwitch = true
        keepAlive = false
        settingsManager.setKeepAlive(false)
        val sw = settingsInner.findViewWithTag<SwitchMaterial>("keepAliveSwitch")
        sw?.isChecked = false
        suppressSwitch = false
        if (granted) {
            startForegroundService(Intent(this, TermKeepAliveService::class.java))
            keepAlive = true
            settingsManager.setKeepAlive(true)
            showTempStatus(getString(R.string.keepalive_on))
            ensureIgnoreBatteryOptimizations()
        } else {
            showTempStatus(getString(R.string.notif_perm_needed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadThemeColors()
        uiBuilder = UiBuilder(this, theme)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "term-lou-keepalive", "TermLou", NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.perm_bg) }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
        if (!prefs.getBoolean("fgServiceWarmed", false)) {
            val warmIntent = Intent(this, TermKeepAliveService::class.java)
            ContextCompat.startForegroundService(this, warmIntent)
            mainHandler.postDelayed({
                stopService(warmIntent)
                prefs.edit().putBoolean("fgServiceWarmed", true).apply()
            }, 300)
        }
        loadSettings()
        OverlayBridge.acquire(this, TermlouDirs.base(this))
        ClipboardBridge.acquire(this, TermlouDirs.base(this))
        TermlouDirs.migrateFromWorkspace(this, wsFiles)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cSurface)
        }

        statusText = uiBuilder.createStatusBar()

        val (newTabHost, newTabIndicator) = uiBuilder.createTabHost()
        tabHost = newTabHost
        tabIndicator = newTabIndicator

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
        }
        val tabViews = uiBuilder.createTabBar()
        terminalTab = tabViews[0]
        filesTab = tabViews[1]
        networkTab = tabViews[2]
        settingsTab = tabViews[3]
        for (tv in tabViews) toolbar.addView(tv)
        tabHost.addView(toolbar)

        val setupViews = uiBuilder.createSetupArea(
            onInstallClick = { btn -> installRootfs(btn) }
        )
        setupArea = setupViews.area
        progressBar = setupViews.progressBar
        progressText = setupViews.progressText

        terminalArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        terminalView = TerminalView(this, null).apply {
            setTerminalViewClient(this@MainActivity)
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
                    if (dx < -swipeThreshold && currentTab == 0 && setupArea.visibility != View.VISIBLE) {
                        showFilesView()
                    }
                }
                false
            }
        }

        val density = resources.displayMetrics.density
        wheelCardH = (52 * density).toInt()

        wheelRecycler = NonFlingRecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        wheelPanel = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, wheelCardH,
                Gravity.BOTTOM
            )
            setBackgroundColor(Color.parseColor("#B31E1E1E"))
            visibility = View.GONE
            addView(wheelRecycler)
        }

        upperWheelPanel = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, wheelCardH,
                Gravity.BOTTOM
            ).apply { bottomMargin = wheelCardH }
            setBackgroundColor(Color.parseColor("#B31E1E1E"))
            visibility = View.GONE
        }
        upperWheelRecycler = NonFlingRecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        upperWheelPanel.addView(upperWheelRecycler)

        wheelController = WheelController(
            this, lifecycleScope, shortcutManager,
            wheelPanel, wheelRecycler, upperWheelPanel, upperWheelRecycler,
            wheelCardH,
            onStatusRestore = { restoreTerminalStatus() }
        )

        val terminalWrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(terminalView)
            addView(upperWheelPanel)
            addView(wheelPanel)
        }
        terminalArea.addView(terminalWrapper)

        rowTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        rowBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        columnsWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(rowTop)
            addView(rowBottom)
        }
        shortcutInner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cSurface)
            addView(columnsWrapper)
        }
        shortcutContainer = SwipeableContainer(this)
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
        shortcutContainer.onLongSwipeLeft = { openShortcutSettings() }
        shortcutContainer.onClickPassthrough = { ev ->
            performClickAt(shortcutContainer, ev.rawX, ev.rawY)
        }

        refreshAllRows()

        filesArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        filesHeader = TextView(this).apply {
            text = "  /workspace/"
            setTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_COMPACT
            setPadding(16, 4, 16, 4)
            setBackgroundColor(cSurfaceVariant)
            visibility = View.GONE
        }
        fileList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val filesScroll = TabSwipeScrollView(
            this,
            onSwipeRight = { if (currentTab == 1 && setupArea.visibility != View.VISIBLE) showTerminalView() },
            onSwipeLeft = { if (currentTab == 1 && setupArea.visibility != View.VISIBLE) showTab(2) }
        )
        filesScroll.addView(fileList)

        filesListRoot = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply {
                weight = 1f
            }
            addView(filesScroll, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        filesArea.addView(filesHeader)
        filesArea.addView(filesListRoot)

        filesArea.addView(uiBuilder.createFileBottomBar(
            onImportClick = { importLauncher.launch(arrayOf("*/*")) },
            onImportFolderClick = { importFolderLauncher.launch(null) }
        ))

        networkArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        netMenuHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply {
                weight = 1f
            }
        }
        val networkScroll = TabSwipeScrollView(
            this,
            onSwipeRight = { if (currentTab == 2 && setupArea.visibility != View.VISIBLE) showFilesView() },
            onSwipeLeft = { if (currentTab == 2 && setupArea.visibility != View.VISIBLE) showTab(3) }
        ).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        networkInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        networkScroll.addView(networkInner)
        netMenuHost.addView(networkScroll)
        buildNetworkContent()
        networkArea.addView(netMenuHost)
        networkArea.addView(createNetBottomBar())

        netFlowListener = { mainHandler.post { scheduleNetFlows() } }
        FlowLog.subscribe(netFlowListener!!)
        netRulesListener = { mainHandler.post { scheduleNetFlows() } }
        BlockRules.subscribe(netRulesListener!!)

        val settingsScroll = TabSwipeScrollView(
            this,
            onSwipeRight = { if (currentTab == 3 && setupArea.visibility != View.VISIBLE) showTab(2) }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply {
                weight = 1f
            }
        }
        settingsInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        settingsScroll.addView(settingsInner)
        buildSettingsContent()

        val settingsFooter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 0, 32, 0)
        }
        settingsFooter.addView(TextView(this).apply {
            text = "TermLou v${try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "1.3.1" }}"
            setTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 4)
        })
        settingsFooter.addView(TextView(this).apply {
            val ss = SpannableString("Made by Lou with ♥")
            ss.setSpan(ForegroundColorSpan(Color.RED), ss.indexOf("♥"), ss.length, 0)
            text = ss
            setTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        })

        settingsWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        settingsWrapper.addView(settingsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        settingsWrapper.addView(settingsFooter)

        content.addView(statusText)
        content.addView(tabHost)
        content.addView(setupArea)

        tabHost.post {
            val tabW = tabHost.width / 4
            if (tabW > 0) {
                val lp = tabIndicator.layoutParams
                lp.width = tabW
                tabIndicator.layoutParams = lp
                tabIndicator.translationX = (currentTab * tabW).toFloat()
            }
        }

        slideContainer = FrameLayout(this)
        slideContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        slideContainer.addView(terminalArea, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        slideContainer.addView(filesArea, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        slideContainer.addView(networkArea, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        slideContainer.addView(settingsWrapper, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        content.addView(slideContainer)

        rootLayout = FrameLayout(this)
        rootLayout.addView(content)
        setContentView(rootLayout)
        notifyPreviousCrash()

        terminalArea.visibility = View.GONE
        filesArea.visibility = View.GONE
        networkArea.visibility = View.GONE
        settingsWrapper.visibility = View.GONE

        if (File(lxRoot, "etc/passwd").exists()) {
            setupArea.visibility = View.GONE
            fromTile = intent.getStringExtra("tile_command") != null
            val splash = SplashView(this, loadSplashCells())
            rootLayout.addView(splash)
            splash.bringToFront()
            splashStartTime = System.currentTimeMillis()
            startShell(splash)
        } else {
            terminalArea.visibility = View.GONE
            filesArea.visibility = View.GONE
            networkArea.visibility = View.GONE
            settingsWrapper.visibility = View.GONE
            setupArea.visibility = View.VISIBLE
        }

        if (keepAlive && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, TermKeepAliveService::class.java))
        }
        handleShareIntent(intent)
        pendingTileCommand = if (fromTile) intent.getStringExtra("tile_command") else null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        fromTile = intent.getStringExtra("tile_command") != null
        handleShareIntent(intent)
        if (fromTile) {
            val cmd = intent.getStringExtra("tile_command")
            if (cmd != null && cmd.isNotBlank() && terminalManager.session != null) {
                showTerminalView()
                lifecycleScope.launch(Dispatchers.IO) {
                    delay(200)
                    val data = (cmd + "\n").toByteArray()
                    terminalManager.session?.write(data, 0, data.size)
                }
            } else {
                pendingTileCommand = cmd
            }
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val name = getFileName(uri) ?: "shared_${System.currentTimeMillis()}"
                val dir = wsFiles
                dir.mkdirs()
                val dest = resolveUniqueFile(dir, name)
                val input = contentResolver.openInputStream(uri)
                if (input == null) {
                    withContext(Dispatchers.Main) {
                        Snackbar.make(rootLayout, getString(R.string.share_read_fail), Snackbar.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                input.use { src -> dest.outputStream().use { dst -> src.copyTo(dst) } }
                withContext(Dispatchers.Main) {
                    refreshFileList()
                    Snackbar.make(rootLayout, getString(R.string.share_saved), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Snackbar.make(rootLayout, getString(R.string.save_failed), Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resolveUniqueFile(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var file = File(dir, name)
        var counter = 1
        while (file.exists()) {
            file = File(dir, "$base($counter)$ext")
            counter++
        }
        return file
    }

    private fun createShortcutKey(label: String, seq: String, hasCtrl: Boolean = false, ctrlSeq: String = "", widthPx: Int = 0): Button {
        return uiBuilder.createShortcutKey(label, seq, hasCtrl, ctrlSeq, widthPx) { s, h, c ->
            val armed = terminalManager.ctrlMode
            val finalSeq = if (armed && h) c else s
            if (armed) {
                setCtrlArmed(false, if (h) null else "Ctrl OFF", if (h) 0 else 1000L)
            }
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val bytes = finalSeq.toByteArray()
                    terminalManager.session?.write(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    Log.e("shortcut", "write failed", e)
                }
            }
        }
    }

    private fun createCtrlKey(widthPx: Int = 0): Button {
        val btn = uiBuilder.createCtrlKey(widthPx) {
            if (terminalManager.ctrlMode) {
                setCtrlArmed(false, "Ctrl OFF", 1000L)
            } else {
                setCtrlArmed(true, null, 0)
            }
        }
        ctrlButton = btn
        return btn
    }

    private fun updateCtrlButtonColor() {
        val color = if (terminalManager.ctrlMode) cError else cOutline
        ctrlButton?.let { ButtonStyle.apply(it, color) }
    }

    private fun hideIme() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val token = currentFocus?.windowToken ?: rootLayout.windowToken
        if (token != null) imm.hideSoftInputFromWindow(token, 0)
    }

    private fun showTab(tabIndex: Int) {
        if (tabIndex != 0) hideIme()
        setupArea.visibility = View.GONE
        val prev = currentTab
        currentTab = tabIndex

        val tabs = listOf(terminalTab, filesTab, networkTab, settingsTab)
        val views = listOf(terminalArea, filesArea, networkArea, settingsWrapper)

        for (i in 0..3) {
            tabs[i].setTextColor(if (i == tabIndex) Color.WHITE else cSurfaceVariant)
        }
        if (tabIndex != prev) {
            tabs[tabIndex].animate().cancel()
            tabs[tabIndex].alpha = 0f
            tabs[tabIndex].animate().alpha(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        } else {
            tabs[tabIndex].alpha = 1f
        }
        animateTabIndicator(tabIndex)
        statusGen++
        refreshStatusBar()

        val showView = views[tabIndex]
        if (showView.visibility != View.VISIBLE) {
            val visibleIndex = views.indexOfFirst { it.visibility == View.VISIBLE }
            if (visibleIndex >= 0) {
                val fromRight = tabIndex > visibleIndex
                animateTo(showView, views[visibleIndex], fromRight)
            } else {
                for (i in views.indices) {
                    views[i].visibility = if (i == tabIndex) View.VISIBLE else View.GONE
                }
            }
        }

        if (tabIndex == 0) terminalView.requestFocus()
        if (tabIndex == 1) refreshFileList()
        if (tabIndex == 2) refreshNetTab()
    }

    private fun animateTabIndicator(tabIndex: Int) {
        val tabW = tabHost.width / 4
        if (tabW <= 0) return
        tabIndicator.animate()
            .translationX((tabIndex * tabW).toFloat())
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun showTerminalView() {
        showTab(0)
    }

    private fun showFilesView() {
        showTab(1)
    }

    private fun animateTo(show: View, hide: View, fromRight: Boolean) {
        if (animating) return
        val w = slideContainer.width
        if (w <= 0) {
            show.visibility = View.VISIBLE
            hide.visibility = View.GONE
            return
        }
        animating = true

        val cd = slideContainer.height * 10f
        val pivotX = w / 2f
        show.cameraDistance = cd
        hide.cameraDistance = cd
        show.pivotX = pivotX
        show.pivotY = slideContainer.height / 2f
        hide.pivotX = pivotX
        hide.pivotY = slideContainer.height / 2f

        val dir = if (fromRight) -1f else 1f
        val half = 125L

        show.rotationY = -dir * 90f
        show.alpha = DIM
        show.visibility = View.VISIBLE
        show.bringToFront()

        hide.animate().rotationY(dir * 90f).alpha(DIM).setDuration(half).withEndAction {
            hide.rotationY = 0f
            hide.alpha = 1f
            hide.visibility = View.GONE
            show.animate().rotationY(0f).alpha(1f).setDuration(half).withEndAction {
                show.alpha = 1f
                animating = false
            }
        }
    }

    private fun getRelativePath(): String {
        val base = wsFiles.absolutePath
        val cur = fileListManager.getCurrentDir().absolutePath
        return if (cur.isEmpty() || cur == "/" || cur == base) "" else cur.removePrefix(base).replace('\\', '/')
    }

    private fun refreshFileList() {
        if (fileListManager.getCurrentDir().path.isEmpty()) {
            fileListManager.setCurrentDir(wsFiles)
        }
        fileListManager.setCurrentTab(currentTab)
        fileListManager.setStatusText(statusText)
        fileListManager.setMainHandler(mainHandler)
        filesListRoot?.let { fileListManager.setMenuHost(it) }
        fileListManager.setOnExportFolder { shareFolder(it) }
        fileListManager.setOnExportFile { shareFile(it) }
        fileListManager.refreshFileList(fileList, { /* navigateUp callback */ }) { /* onFileClick callback */ }
    }

    private fun applyFontSettings() {
        terminalView.setTextSize(fontSizeSp)
        showTempStatus(getString(R.string.font_changed_fmt, fontNames()[fontSizeIndex]))
    }

    private fun attachFontSliderAnimation(slider: Slider) {
        val maxStep = (slider.valueTo - slider.valueFrom).toInt()
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
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
        fontSizeSp = fontSizes[idx]
        settingsManager.setFontSizeIndex(idx)
        applyFontSettings()
    }

    private fun buildSettingsContent() {
        val density = resources.displayMetrics.density
        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.settings_font_title)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_TITLE
            setPadding(0, 0, 0, 16)
        })

        val slider = TickSlider(ContextThemeWrapper(this, R.style.Theme_TermLou_Slider)).apply {
            valueFrom = 0f
            valueTo = 4f
            stepSize = 0f
            value = fontSizeIndex.toFloat()
            thumbTintList = ColorStateList.valueOf(cPrimary)
            trackActiveTintList = ColorStateList.valueOf(cPrimary)
            trackInactiveTintList = ColorStateList.valueOf(cOutline)
            haloTintList = ColorStateList.valueOf((cPrimary and 0x00FFFFFF) or (0x33 shl 24))
            setTickColors(cPrimary, (cOutline and 0x00FFFFFF) or (0x66 shl 24))
        }
        settingsInner.addView(slider)
        attachFontSliderAnimation(slider)

        settingsInner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(cOutline)
        })

        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.settings_shell_title)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_TITLE
            setPadding(0, 0, 0, 4)
        })

        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.settings_shell_desc)
            setTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 12)
        })

        val shellEdit = EditText(this).apply {
            setText(shellCmd)
            setTextColor(Color.WHITE)
            setBackgroundColor(cOutline)
            textSize = UiTokens.TEXT_BODY
            setPadding(12, 8, 12, 8)
            setHint(getString(R.string.hint_shell_cmd))
            setHintTextColor(cOnSurfaceVariant)
        }
        settingsInner.addView(shellEdit)

        val shellBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }
        val saveShellBtn = Button(this).apply {
            text = getString(R.string.save)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            setPadding(16, 6, 16, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 4
            }
            ButtonStyle.apply(this, cPrimary)
            setOnClickListener {
                val cmd = shellEdit.text.toString().trim()
                shellCmd = cmd
                settingsManager.setShellCmd(shellCmd)
                showTempStatus(getString(R.string.shell_saved))
                hideIme()
            }
        }
        val resetShellBtn = Button(this).apply {
            text = getString(R.string.reset)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            setPadding(16, 6, 16, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 4
            }
            ButtonStyle.apply(this, cOutline)
            setOnClickListener {
                shellEdit.setText("")
                shellCmd = ""
                settingsManager.setShellCmd("")
                showTempStatus(getString(R.string.shell_inited_idle))
                hideIme()
            }
        }
        shellBtnRow.addView(saveShellBtn)
        shellBtnRow.addView(resetShellBtn)
        settingsInner.addView(shellBtnRow)

        settingsInner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(cOutline)
        })

        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.settings_tile_title)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_TITLE
            setPadding(0, 0, 0, 4)
        })

        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.settings_tile_desc)
            setTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 12)
        })

        val tileEdit = EditText(this).apply {
            setText(tileCommand)
            setTextColor(Color.WHITE)
            setBackgroundColor(cOutline)
            textSize = UiTokens.TEXT_BODY
            setPadding(12, 8, 12, 8)
            setHint(getString(R.string.hint_tile_cmd))
            setHintTextColor(cOnSurfaceVariant)
        }
        settingsInner.addView(tileEdit)

        val tileBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }
        val saveTileBtn = Button(this).apply {
            text = getString(R.string.save)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            setPadding(16, 6, 16, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 4
            }
            ButtonStyle.apply(this, cPrimary)
            setOnClickListener {
                val cmd = tileEdit.text.toString().trim()
                tileCommand = cmd
                settingsManager.setTileCommand(cmd)
                showTempStatus(getString(R.string.tile_cmd_saved))
                hideIme()
            }
        }
        val resetTileBtn = Button(this).apply {
            text = getString(R.string.reset)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            setPadding(16, 6, 16, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 4
            }
            ButtonStyle.apply(this, cOutline)
            setOnClickListener {
                tileEdit.setText(shellCmd)
                tileCommand = shellCmd
                settingsManager.setTileCommand(shellCmd)
                showTempStatus(getString(R.string.inited))
                hideIme()
            }
        }
        tileBtnRow.addView(saveTileBtn)
        tileBtnRow.addView(resetTileBtn)
        settingsInner.addView(tileBtnRow)

        settingsInner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(cOutline)
        })

        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.settings_quick_title)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_TITLE
            setPadding(0, 0, 0, 4)
        })

        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.settings_quick_desc)
            setTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 12)
        })

        val quickBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
        }
        val appBtn = Button(this).apply {
            text = getString(R.string.pick_app)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            setPadding(16, 6, 16, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 4
            }
            ButtonStyle.apply(this, cPrimary)
            setOnClickListener { showAppPicker() }
        }
        val initBtn = Button(this).apply {
            text = getString(R.string.reset)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            setPadding(16, 6, 16, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 4
            }
            ButtonStyle.apply(this, cOutline)
            setOnClickListener { onQuickInitClick() }
        }
        quickBtnRow.addView(appBtn)
        quickBtnRow.addView(initBtn)
        settingsInner.addView(quickBtnRow)

        settingsInner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(cOutline)
        })

        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.settings_dialog_ws_title)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_TITLE
            setPadding(0, 0, 0, 4)
        })

        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.settings_dialog_ws_desc)
            setTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 12)
        })

        settingsInner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.open_workshop)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                ButtonStyle.apply(this, cPrimary)
                setOnClickListener { openDialogMaker() }
            })
        })

        settingsInner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(cOutline)
        })

        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.settings_splash_title)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_TITLE
            setPadding(0, 0, 0, 4)
        })

        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.settings_splash_desc)
            setTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 12)
        })

        settingsInner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.open_workshop)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                ButtonStyle.apply(this, cPrimary)
                setOnClickListener { openSplashMaker() }
            })
        })

        settingsInner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(cOutline)
        })

        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.settings_advanced)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_TITLE
            setPadding(0, 0, 0, (4 * density).toInt())
        })
        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.upstream_title)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_BODY
            setPadding(0, 0, 0, 4)
        })
        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.upstream_desc)
            setTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, (12 * density).toInt())
        })
        val upEdit = EditText(this).apply {
            setText(settingsManager.netUpstream())
            setTextColor(Color.WHITE)
            setHintTextColor(cOnSurfaceVariant)
            setHint("socks5://127.0.0.1:1080")
            textSize = UiTokens.TEXT_BODY
            setSingleLine(true)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            setBackgroundColor(UiTokens.searchBg)
        }
        settingsInner.addView(upEdit)
        settingsInner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * density).toInt(), 0, 0)
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.save)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                isAllCaps = false
                setPadding(16, 6, 16, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 4
                }
                ButtonStyle.apply(this, cPrimary)
                setOnClickListener {
                    settingsManager.setNetUpstream(upEdit.text.toString().trim())
                    showTempStatus(getString(R.string.upstream_saved))
                    hideIme()
                }
            })
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.clear)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                isAllCaps = false
                setPadding(16, 6, 16, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 4
                }
                ButtonStyle.apply(this, cOutline)
                setOnClickListener {
                    upEdit.setText("")
                    settingsManager.setNetUpstream("")
                    showTempStatus(getString(R.string.upstream_cleared))
                    hideIme()
                }
            })
        })

        settingsInner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(cOutline)
        })

        settingsInner.addView(TextView(this).apply {
            text = getString(R.string.lan_title)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_BODY
            setPadding(0, 0, 0, 4)
        })
        lanStatusText = TextView(this).apply {
            text = getString(R.string.lan_status_off)
            setTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 4)
        }
        settingsInner.addView(lanStatusText)
        lanUrlText = TextView(this).apply {
            text = getString(R.string.lan_url_preview)
            setTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 12)
            setOnClickListener { copyLanUrl() }
        }
        settingsInner.addView(lanUrlText)
        settingsInner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * density).toInt(), 0, 0)
            lanToggleBtn = Button(this@MainActivity).apply {
                text = getString(R.string.lan_start)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 4
                }
                ButtonStyle.apply(this, cPrimary)
                setOnClickListener {
                    if (LanShareService.isRunning) stopLan() else startLan()
                }
            }
            lanAuthBtn = Button(this@MainActivity).apply {
                text = getString(R.string.lan_auth_btn)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 4
                }
                ButtonStyle.apply(this, cOutline)
                setOnClickListener { showLanAuthDialog() }
            }
            addView(lanToggleBtn)
            addView(lanAuthBtn)
        })
        refreshLanRow()

        settingsInner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.keepalive_title)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                textSize = UiTokens.TEXT_BODY
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(SwitchMaterial(ContextThemeWrapper(this@MainActivity, R.style.Theme_TermLou_Slider)).apply {
                tag = "keepAliveSwitch"
                elevation = 8f
                thumbTintList = controlTint()
                trackTintList = switchTrackTint()
                isChecked = keepAlive
                setOnCheckedChangeListener { _, isChecked ->
                    if (suppressSwitch) return@setOnCheckedChangeListener
                    if (isChecked) {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            startForegroundService(Intent(this@MainActivity, TermKeepAliveService::class.java))
                            keepAlive = true
                            settingsManager.setKeepAlive(true)
                            showTempStatus(getString(R.string.keepalive_on))
                            ensureIgnoreBatteryOptimizations()
                        } else {
                            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        stopService(Intent(this@MainActivity, TermKeepAliveService::class.java))
                        keepAlive = false
                        settingsManager.setKeepAlive(false)
                        showTempStatus(getString(R.string.keepalive_off))
                    }
                }
            })
        })

        settingsInner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.storage_title)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                textSize = UiTokens.TEXT_TITLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.view)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                ButtonStyle.apply(this, cOutline)
                setOnClickListener { showStorageDialog() }
            })
        })

        settingsInner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(cOutline)
        })

        val langZh = AppLang.isChinese(this)
        settingsInner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.lang_title) + " " + if (langZh) "🇨🇳" else "🇺🇸"
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                textSize = UiTokens.TEXT_TITLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(SwitchMaterial(ContextThemeWrapper(this@MainActivity, R.style.Theme_TermLou_Slider)).apply {
                elevation = 8f
                thumbTintList = controlTint()
                trackTintList = switchTrackTint()
                isChecked = langZh
                setOnCheckedChangeListener { _, isChecked ->
                    if (suppressSwitch) return@setOnCheckedChangeListener
                    settingsManager.setLangExplicit(if (isChecked) AppLang.LANG_ZH else AppLang.LANG_EN)
                    AppLang.apply(this@MainActivity)
                    NetVpnService.refreshLocale()
                    LanShareService.refreshLocale()
                    TermKeepAliveService.refreshLocale()
                    runCatching {
                        TileService.requestListeningState(
                            this@MainActivity, ComponentName(this@MainActivity, CommandTileService::class.java)
                        )
                        TileService.requestListeningState(
                            this@MainActivity, ComponentName(this@MainActivity, LauncherTileService::class.java)
                        )
                    }
                    recreate()
                }
            })
        })
        settingsInner.addView(TextView(this).apply {
            text = getString(if (langZh) R.string.lang_sub_zh else R.string.lang_sub_en)
            setTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 0)
        })
    }

    private fun controlTint(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        ),
        intArrayOf(cPrimary, cOutline)
    )

    private fun switchTrackTint(): ColorStateList {
        val checked = (cPrimary and 0x00FFFFFF) or (0x66 shl 24)
        val unchecked = (cOutline and 0x00FFFFFF) or (0x66 shl 24)
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(checked, unchecked)
        )
    }

    private fun loadShortcuts(): MutableList<ShortcutItem> = settingsManager.loadShortcuts(this)

    private fun saveShortcuts(list: List<ShortcutItem>) {
        settingsManager.saveShortcuts(list)
        refreshAllRows()
    }

    private fun refreshAllRows() {
        shortcutManager.refreshAllRows(rowTop, rowBottom, shortcutInner, ::createShortcutKey, ::createCtrlKey)
        wheelController?.refreshAll()
    }

    private fun refreshStatusBar() {
        if (isFinishing || isDestroyed) return
        statusText.text = when (currentTab) {
            1 -> {
                val p = getRelativePath()
                if (p.isEmpty()) "Files" else "Files | $p"
            }
            2 -> {
                val s = NetVpnService.statusText.substringBefore('\n')
                if (NetVpnService.isRunning) "Network | $s"
                else if (settingsManager.loadCaptureApps().isEmpty()) getString(R.string.net_no_apps)
                else "Network"
            }
            3 -> "Settings"
            else -> terminalBaseText()
        }
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

    private fun openShortcutSettings() {
        needRefreshShortcuts = true
        startActivity(Intent(this, ShortcutSettingsActivity::class.java))
    }

    private fun openDialogMaker() {
        startActivity(Intent(this, DialogMakerActivity::class.java))
    }

    private fun openSplashMaker() {
        startActivity(Intent(this, SplashMakerActivity::class.java))
    }

    /** 读取启动工坊保存的点阵（splash.json → List<Pair<r,c>>）；无则 null（走默认 LOGO）。 */
    private fun loadSplashCells(): List<Pair<Int, Int>>? {
        runCatching {
            val f = TermlouDirs.base(this)
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

    override fun onResume() {
        super.onResume()
        if (needRefreshShortcuts) {
            needRefreshShortcuts = false
            refreshAllRows()
        }
        if (wheelPanel.visibility == View.VISIBLE) wheelController?.onResume()
        if (currentTab == 2) refreshNetTab()
        refreshStatusBar()
        if (batteryOptPending) {
            batteryOptPending = false
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                showTempStatus(getString(R.string.battery_exempted))
            }
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        tabHost.post {
            val tabW = tabHost.width / 4
            if (tabW > 0) {
                val lp = tabIndicator.layoutParams
                lp.width = tabW
                tabIndicator.layoutParams = lp
                tabIndicator.translationX = (currentTab * tabW).toFloat()
            }
        }
    }

    private fun ensureIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            batteryOptPending = true
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                batteryOptPending = true
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                showTempStatus(getString(R.string.battery_manual))
            }
        }
    }

    private fun showCardEditDialog(index: Int, oldLabel: String, oldCmd: String) {
        shortcutManager.showCardEditDialog(index, oldLabel, oldCmd) { refreshAllRows() }
    }

    private fun showStorageDialog() {
        StorageDialog(this, theme, wsFiles, lifecycleScope).show()
    }

    private fun loadSettings() {
        settingsManager = SettingsManager(prefs)
        settingsManager.load()
        fontSizeIndex = settingsManager.fontSizeIndex
        fontSizeSp = settingsManager.fontSizeSp
        shellCmd = settingsManager.shellCmd
        tileCommand = settingsManager.tileCommand
        keepAlive = settingsManager.keepAlive
        terminalManager = TerminalManager(this, lxRoot, wsFiles, wsTmp)
        shortcutManager = ShortcutManager(
            this, theme, settingsManager,
            writeFn = { cmd ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val bytes = cmd.toByteArray()
                        terminalManager.session?.write(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        Log.e("shortcut", "write failed", e)
                    }
                }
            },
            onCardUsed = { label, state, count ->
                lastClickedInfo = "$state : $label <$count>"
                showTerminalTemp("Terminal | $lastClickedInfo", 2000L)
            },
            onCommandExecuted = { wheelController?.hide() }
        )
        fileListManager = FileListManager(this, theme)
    }

    private fun terminalBaseText(): String =
        if (lastClickedInfo.isEmpty()) "Terminal" else "Terminal | $lastClickedInfo"

    private fun restoreTerminalStatus() {
        terminalTempJob?.cancel()
        terminalTempJob = null
        terminalTempActive = false
        lastClickedInfo = ""
        if (currentTab == 0) statusText.text = terminalBaseText()
    }

    private fun showTerminalTemp(text: String, durationMs: Long) {
        terminalTempJob?.cancel()
        terminalTempActive = true
        setStatusTextAnimated(text)
        terminalTempJob = lifecycleScope.launch {
            delay(durationMs)
            terminalTempActive = false
            lastClickedInfo = ""
            if (currentTab == 0) statusText.text = terminalBaseText()
        }
    }

    private fun setCtrlArmed(armed: Boolean, feedback: String?, feedbackMs: Long) {
        ctrlArmJob?.cancel()
        ctrlArmJob = null
        terminalManager.setCtrlMode(armed)
        updateCtrlButtonColor()
        if (armed) {
            terminalTempJob?.cancel()
            terminalTempJob = null
            terminalTempActive = true
            setStatusTextAnimated("Ctrl ON (next key)")
            ctrlArmJob = lifecycleScope.launch {
                delay(3000)
                if (terminalManager.ctrlMode) {
                    terminalManager.setCtrlMode(false)
                    updateCtrlButtonColor()
                    showTerminalTemp(getString(R.string.ctrl_reset), 1000L)
                } else {
                    restoreTerminalStatus()
                }
            }
        } else if (feedback != null) {
            showTerminalTemp(feedback, feedbackMs)
        } else {
            restoreTerminalStatus()
        }
    }

    private fun setStatusTextAnimated(text: String) {
        statusText.animate().cancel()
        statusText.animate().alpha(0f).setDuration(120).withEndAction {
            statusText.text = text
            statusText.animate().alpha(1f).setDuration(120).start()
        }.start()
    }

    private fun showTempStatus(msg: String) {
        if (currentTab != 2 && currentTab != 3) return
        statusGen++
        setStatusTextAnimated(msg)
        settingsStatusJob?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            statusGen++
            refreshStatusBar()
            settingsStatusJob = null
        }
        settingsStatusJob = r
        mainHandler.postDelayed(r, 2000)
    }

    private fun showAppPicker() {
        AppPickerDialog(this, cPrimary, cOnSurfaceVariant, loadAppCache(), settingsManager, theme).show()
    }

    private fun loadAppCache(): List<AppEntry> {
        cachedApps?.let { return it }
        val list = runCatching { packageManager.getInstalledApplications(0) }
            .getOrElse { emptyList() }
            .filter { it.enabled }
            .mapNotNull { ai ->
                runCatching {
                    val pkg = ai.packageName
                    if (packageManager.getLaunchIntentForPackage(pkg) == null) null
                    else AppEntry(
                        pkg,
                        packageManager.getApplicationLabel(ai).toString(),
                        null,
                        (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
        cachedApps = list
        return list
    }

    private fun buildNetworkContent() {
        netBraceMenu = BraceMenu(this)
        netFlowList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        networkInner.addView(netFlowList)
    }

    private fun createNetBottomBar(): LinearLayout {
        fun barButton(text: String, color: Int, onClick: () -> Unit): Button = Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            isAllCaps = false
            setPadding(3, 0, 3, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            ButtonStyle.apply(this, color)
            setOnClickListener { onClick() }
        }
        fun gridRow(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 2, 4, 2)
            for (b in buttons) addView(b)
        }
        netToggleBtn = barButton(getString(R.string.net_capture_start), cPrimary) {
            if (NetVpnService.isRunning) stopNet() else startNet()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cSurface)
            addView(gridRow(
                netToggleBtn,
                barButton(getString(R.string.net_pick_apps), cOutline) {
                    NetAppPickerDialog(
                        this@MainActivity, cPrimary, cOnSurfaceVariant, loadAppCache(), settingsManager,
                        { refreshNetTab() }, theme
                    ).show()
                }
            ))
            addView(gridRow(
                barButton(getString(R.string.net_clear), cOutline) {
                    FlowLog.clear()
                    renderNetFlows()
                    showTempStatus(getString(R.string.net_cleared))
                },
                barButton(getString(R.string.net_view_log), cOutline) { showNetLogDialog() }
            ))
        }
    }

    private fun showNetLogDialog() {
        val density = resources.displayMetrics.density
        val log = NetVpnService.readLogTail(this, 200)
        val startLog = runCatching {
            File(filesDir, "net/start.log").readText()
        }.getOrDefault("")
        val miniLog = runCatching {
            val f = File(filesDir, "net/minisocks.log")
            if (f.exists()) f.readLines().takeLast(60).joinToString("\n") else ""
        }.getOrDefault("")
        val content = buildString {
            if (startLog.isNotBlank()) { append("== start.log ==\n").append(startLog) }
            append(getString(R.string.net_log_tail_tun))
            append(if (log.isBlank()) getString(R.string.net_log_empty) else log)
            append(getString(R.string.net_log_tail_mini))
            append(if (miniLog.isBlank()) getString(R.string.net_log_empty) else miniLog)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.net_log_title))
            .setView(ScrollView(this).apply {
                addView(TextView(this@MainActivity).apply {
                    text = content
                    setTextColor(Color.WHITE)
                    textSize = UiTokens.TEXT_COMPACT
                    typeface = Typeface.MONOSPACE
                    setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
                })
            })
            .setPositiveButton(getString(R.string.close), null)
            .create()
        DialogStyler.apply(dialog, theme)
        dialog.show()
    }

    private fun refreshNetTab() {
        if (isFinishing || isDestroyed) return
        if (::netToggleBtn.isInitialized) {
            val running = NetVpnService.isRunning
            val hasApps = settingsManager.loadCaptureApps().isNotEmpty()
            netToggleBtn.text = if (running) getString(R.string.net_capture_stop) else getString(R.string.net_capture_start)
            netToggleBtn.setTextColor(Color.WHITE)
            if (!running && !hasApps) {
                netToggleBtn.isEnabled = false
                netToggleBtn.alpha = 0.5f
                ButtonStyle.apply(netToggleBtn, cOutline)
            } else {
                netToggleBtn.isEnabled = true
                netToggleBtn.alpha = 1f
                ButtonStyle.apply(netToggleBtn, if (running) cError else cPrimary)
            }
        }
        renderNetFlows()
        val status = NetVpnService.statusText.substringBefore('\n')
        if (!NetVpnService.isRunning && status.isNotEmpty() && status != getString(R.string.vpn_idle)) {
            if (status != lastNetStatusShown) {
                lastNetStatusShown = status
                showTempStatus("Network | $status")
            }
        } else {
            lastNetStatusShown = null
        }
        refreshStatusBar()
    }

    private fun renderNetFlows() {
        if (isFinishing || isDestroyed) return
        if (!::netFlowList.isInitialized) return
        netFlowList.removeAllViews()
        val rows = FlowLog.list()
        if (rows.isEmpty()) {
            netFlowList.addView(TextView(this).apply {
                text = getString(R.string.net_empty)
                setTextColor(cOnSurfaceVariant)
                textSize = UiTokens.TEXT_COMPACT
                setPadding(0, 8, 0, 8)
            })
            return
        }
        val density = resources.displayMetrics.density
        val limit = minOf(rows.size, 80)
        for (i in 0 until limit) {
            val f = rows[i]
            netFlowList.addView(buildFlowRow(f))
            netFlowList.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(cOutline)
            })
        }
    }

    private fun buildFlowRow(f: FlowEntry): View {
        val density = resources.displayMetrics.density
        val ip = f.dstIp
        val domain = f.domain
        val blocked = f.state == "BLOCKED" || BlockRules.isBlocked(ip, domain)
        val main = if (!domain.isNullOrBlank()) domain else ip
        val mainColor = when {
            blocked -> Color.parseColor("#FF6E6E")
            f.proto == "UDP" -> Color.parseColor("#FFD54F")
            else -> UiTokens.statusGreen
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(if (blocked) UiTokens.dirRowBg else Color.TRANSPARENT)
            setOnClickListener { toggleFlowExpand(f.id) }
            setOnLongClickListener {
                showNetBraceMenu(this, f)
                true
            }
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = main
                setTextColor(mainColor)
                textSize = UiTokens.TEXT_BODY
                typeface = Typeface.MONOSPACE
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (blocked) {
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.flow_blocked)
                    setTextColor(Color.parseColor("#FF5252"))
                    textSize = UiTokens.TEXT_META
                    typeface = Typeface.MONOSPACE
                    setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
                    setBackgroundColor(Color.parseColor("#26FF5252"))
                })
            }
        })
        row.addView(TextView(this).apply {
            text = buildFlowInfo(f, domain)
            setTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            typeface = Typeface.MONOSPACE
            setPadding(0, (2 * density).toInt(), 0, 0)
        })
        if (f.id in netExpandedIds) {
            row.addView(TextView(this).apply {
                text = buildFlowDetail(f, domain)
                setTextColor(cOnSurfaceVariant)
                textSize = UiTokens.TEXT_META
                typeface = Typeface.MONOSPACE
                setPadding(0, (2 * density).toInt(), 0, 0)
            })
        }
        return row
    }

    private fun buildFlowInfo(f: FlowEntry, domain: String?): String {
        val stateCn = when (f.state) {
            "OPEN" -> getString(R.string.flow_state_open)
            "UDP" -> getString(R.string.flow_state_udp)
            "BLOCKED" -> getString(R.string.flow_blocked)
            else -> getString(R.string.flow_state_closed)
        }
        return "${f.proto}:${f.dstPort} · ↑${formatBytes(f.bytesUp)} ↓${formatBytes(f.bytesDown)} · $stateCn · ${f.time}"
    }

    private fun buildFlowDetail(f: FlowEntry, domain: String?): String {
        val detail = if (!domain.isNullOrBlank()) {
            getString(R.string.flow_server_fmt, f.dstIp)
        } else {
            getString(R.string.flow_no_domain)
        }
        return "$detail · ${f.proto} ${f.dstPort}"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0)
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024))
        return String.format("%.2fGB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun toggleFlowExpand(id: Long) {
        if (!netExpandedIds.remove(id)) netExpandedIds.add(id)
        renderNetFlows()
    }

    private fun rootDomainOf(domain: String): String {
        val labels = domain.trim().removeSuffix(".").split('.')
        if (labels.size <= 2) return labels.joinToString(".")
        return labels.takeLast(2).joinToString(".")
    }

    private fun showNetBraceMenu(row: View, f: FlowEntry) {
        if (!::netMenuHost.isInitialized || !::netBraceMenu.isInitialized) return
        val ip = f.dstIp
        val domain = f.domain
        val blocked = f.state == "BLOCKED" || BlockRules.isBlocked(ip, domain)
        val rowLoc = IntArray(2)
        val hostLoc = IntArray(2)
        row.getLocationInWindow(rowLoc)
        netMenuHost.getLocationInWindow(hostLoc)
        val ay = (rowLoc[1] - hostLoc[1]).toFloat()
        val rh = row.height.toFloat().coerceAtLeast(1f)
        if (blocked) {
            val matched = if (!domain.isNullOrBlank()) BlockRules.blockedDomainFor(domain) else null
            val opt2Label = matched ?: if (!domain.isNullOrBlank()) rootDomainOf(domain) else null
            netBraceMenu.show(
                netMenuHost, ay, rh, getString(R.string.flow_unblock), opt2Label,
                confirm = opt2Label != null,
                onOpt1 = {
                    if (matched != null) BlockRules.removeBlockDomain(matched)
                    else BlockRules.removeBlockIp(ip)
                    showTempStatus(getString(R.string.flow_unblocked))
                },
                onOpt2 = {
                    if (opt2Label != null) BlockRules.addBlockDomain(opt2Label)
                    showTempStatus(getString(R.string.flow_block_all_fmt, opt2Label))
                }
            )
        } else {
            val opt2Label = if (!domain.isNullOrBlank()) rootDomainOf(domain) else null
            netBraceMenu.show(
                netMenuHost, ay, rh, getString(R.string.flow_block_ip), opt2Label,
                confirm = opt2Label != null,
                onOpt1 = {
                    BlockRules.addBlockIp(ip)
                    showTempStatus(getString(R.string.flow_blocked_ip_fmt, ip))
                },
                onOpt2 = {
                    if (opt2Label != null) BlockRules.addBlockDomain(opt2Label)
                    showTempStatus(getString(R.string.flow_block_all_fmt, opt2Label))
                }
            )
        }
    }

    private fun scheduleNetFlows() {
        netFlowDirty = true
        mainHandler.removeCallbacks(netFlowRunnable)
        mainHandler.postDelayed(netFlowRunnable, 400)
    }

    private fun refreshLanRow() {
        if (isFinishing || isDestroyed) return
        if (!::lanToggleBtn.isInitialized) return
        val running = LanShareService.isRunning
        val user = settingsManager.lanUser()
        lanStatusText.text = if (running) {
            if (user.isEmpty()) getString(R.string.lan_status_open) else getString(R.string.lan_status_auth_fmt, user)
        } else {
            if (user.isEmpty()) getString(R.string.lan_status_off) else getString(R.string.lan_status_off_auth_fmt, user)
        }
        lanUrlText.text = if (running && LanShareService.lanUrl.isNotEmpty()) {
            getString(R.string.lan_url_copy_fmt, LanShareService.lanUrl)
        } else {
            val ip = NetworkUtils.getLanIp(this) ?: "…"
            getString(R.string.lan_url_preview_ip_fmt, ip)
        }
        lanToggleBtn.text = if (running) getString(R.string.lan_stop) else getString(R.string.lan_start)
        ButtonStyle.apply(lanToggleBtn, if (running) cError else cPrimary)
        lanAuthBtn.isEnabled = !running
        lanAuthBtn.alpha = if (running) 0.5f else 1f
    }

    private fun startLan() {
        LanShareService.start(this)
        showTempStatus(getString(R.string.lan_starting))
        mainHandler.postDelayed({ refreshLanRow() }, 1500)
        mainHandler.postDelayed({ refreshLanRow() }, 4000)
    }

    private fun stopLan() {
        LanShareService.stop(this)
        showTempStatus(getString(R.string.lan_stopped))
        mainHandler.postDelayed({ refreshLanRow() }, 1200)
    }

    private fun copyLanUrl() {
        val url = if (LanShareService.isRunning && LanShareService.lanUrl.isNotEmpty()) {
            LanShareService.lanUrl
        } else {
            val ip = NetworkUtils.getLanIp(this) ?: return
            "http://$ip:8080"
        }
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("lan", url))
        }
        showTempStatus(getString(R.string.copied_fmt, url))
    }

    private fun showLanAuthDialog() {
        if (LanShareService.isRunning) {
            showTempStatus(getString(R.string.lan_stop_first))
            return
        }
        val density = resources.displayMetrics.density
        val userEdit = EditText(this).apply {
            setText(settingsManager.lanUser())
            hint = getString(R.string.hint_lan_user)
            setTextColor(Color.WHITE)
            setHintTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_BODY
            setSingleLine(true)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            setBackgroundColor(UiTokens.searchBg)
        }
        val passEdit = EditText(this).apply {
            setText(settingsManager.lanPass())
            hint = getString(R.string.hint_password)
            setTextColor(Color.WHITE)
            setHintTextColor(cOnSurfaceVariant)
            textSize = UiTokens.TEXT_BODY
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            setBackgroundColor(UiTokens.searchBg)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            addView(userEdit)
            addView(passEdit, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.lan_auth_title))
            .setView(body)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                settingsManager.setLanAuth(
                    userEdit.text.toString().trim(),
                    passEdit.text.toString()
                )
                hideIme()
                refreshLanRow()
                showTempStatus(getString(R.string.lan_auth_saved))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        dialog.show()
        DialogStyler.apply(dialog, theme)
    }

    private fun startNet() {
        if (settingsManager.loadCaptureApps().isEmpty()) {
            showTempStatus(getString(R.string.net_pick_first))
            refreshNetTab()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            NetVpnService.start(this)
        }
        mainHandler.postDelayed({ refreshNetTab() }, 1500)
    }

    private fun stopNet() {
        NetVpnService.stop(this)
        showTempStatus(getString(R.string.net_stopped))
        mainHandler.postDelayed({ refreshNetTab() }, 500)
    }

    private fun onQuickInitClick() {
        if (quickInitArmed && statusGen == quickInitArmedGen) {
            settingsManager.clearFavoriteApps()
            quickInitArmed = false
            showTempStatus(getString(R.string.sc_cleared_all))
        } else {
            quickInitArmed = true
            showTempStatus(getString(R.string.sc_init_confirm))
            quickInitArmedGen = statusGen
        }
    }

    private fun importFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                val fileName = getFileName(uri) ?: "imported_file"
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val target = File(fileListManager.getCurrentDir(), fileName)
                        target.outputStream().use { input.copyTo(it) }
                    }
                }
                refreshFileList()
            } catch (e: Exception) {
                statusText.text = getString(R.string.import_failed_fmt, e.message.toString())
            }
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val mime = getMimeType(file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_to)))
        } catch (e: Exception) {
            statusText.text = getString(R.string.share_failed_fmt, e.message.toString())
        }
    }

    private fun getMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "txt" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "md" -> "text/markdown"
            "csv" -> "text/csv"
            "py" -> "text/x-python"
            "sh" -> "application/x-sh"
            else -> "application/octet-stream"
        }
    }

    private fun shareFolder(folder: File) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) { statusText.text = getString(R.string.pack_progress) }
                val zipFile = File(wsTmp, "${folder.name}.zip")
                withContext(Dispatchers.IO) {
                    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                        folder.walkTopDown().forEach { file ->
                            if (file.isFile) {
                                val entryName = folder.parentFile?.let { file.toRelativeString(it) } ?: file.name
                                zos.putNextEntry(ZipEntry(entryName))
                                file.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    shareFile(zipFile)
                    statusText.text = getString(R.string.pack_done)
                }
            } catch (e: Exception) {
                statusText.text = getString(R.string.pack_failed_fmt, e.message.toString())
            }
        }
    }

    private fun syncFolderFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                statusText.text = getString(R.string.import_progress)
                val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, uri) ?: throw Exception(getString(R.string.import_unreadable))
                withContext(Dispatchers.IO) {
                    val folderName = rootDoc.name ?: "imported"
                    val targetDir = File(fileListManager.getCurrentDir(), folderName)
                    targetDir.mkdirs()
                    syncDocuments(rootDoc, targetDir)
                }
                refreshFileList()
                statusText.text = getString(R.string.import_done)
            } catch (e: Exception) {
                statusText.text = getString(R.string.import_failed_fmt, e.message.toString())
            }
        }
    }

    private fun syncDocuments(doc: DocumentFile, targetDir: File) {
        for (child in doc.listFiles()) {
            if (child.isDirectory) {
                val subDir = File(targetDir, child.name ?: "unknown")
                subDir.mkdirs()
                syncDocuments(child, subDir)
            } else if (child.isFile) {
                val name = child.name ?: "unknown"
                child.uri?.let { uri ->
                    contentResolver.openInputStream(uri)?.use { input ->
                        File(targetDir, name).outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String? = FileUtils.getFileName(this, uri)

    private fun formatSize(bytes: Long): String = FileUtils.formatSize(bytes)

    private fun installRootfs(setupBtn: Button) {
        setupBtn.isEnabled = false
        setupBtn.text = getString(R.string.setup_extracting)
        progressBar.isIndeterminate = true
        progressText.text = getString(R.string.setup_extract_rootfs)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RootfsExtractor(this@MainActivity).extractTo(lxRoot)
                }
                progressText.text = "RootFS ready!"
                progressBar.isIndeterminate = false
                progressBar.progress = 100
                setupArea.visibility = View.GONE
                val splash = SplashView(this@MainActivity)
                rootLayout.addView(splash)
                splash.bringToFront()
                startShell(splash)
            } catch (e: Exception) {
                Log.e("RootfsExtractor", "extractTo failed", e)
                progressText.text = "Error: ${e.javaClass.simpleName}: ${e.message}"
                setupBtn.isEnabled = true
                setupBtn.text = getString(R.string.retry)
            }
        }
    }

    private fun syncDnsToRootfs() = terminalManager.syncDnsToRootfs()

    private fun setSplashStatus(splash: SplashView, text: String) {
        splash.post {
            splash.statusText = text
            splash.invalidate()
        }
    }

    private fun startShell(splash: SplashView) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                setSplashStatus(splash, getString(R.string.splash_preparing))
                wsTmp.mkdirs()
                wsTmp.listFiles()?.forEach { it.deleteRecursively() }

                // Migrate old workspace/files/ → workspace/
                val oldWsFiles = File(filesDir, "workspace/files")
                if (oldWsFiles.exists() && oldWsFiles.isDirectory) {
                    oldWsFiles.listFiles()?.forEach { f ->
                        val dest = File(wsFiles, f.name)
                        if (!dest.exists()) f.renameTo(dest)
                    }
                    oldWsFiles.delete()
                }

                wsFiles.mkdirs()

                // Only write bashrc if missing, outdated, or shellCmd changed (preserves user additions)
                val bashrc = File(lxRoot, "root/.bashrc")
                val bashrcState = File(lxRoot, "root/.term_lou_state")
                val savedCmd = if (bashrcState.exists()) bashrcState.readText().trim() else ""
                val effectiveCmd = if (fromTile) "" else shellCmd
                val marker = "# TERMLOU_V6"
                val needWrite = !bashrc.exists() || !bashrc.readText().contains(marker) || savedCmd != effectiveCmd
                if (needWrite) {
                    val cmdLine = if (effectiveCmd.isNotBlank()) {
                        "$effectiveCmd\n"
                    } else ""
                    val content = marker + "\n" +
                        "export PATH=\"\$HOME/.local/bin:\$PATH\"\n" +
                        "alias id='id 2>/dev/null'\nalias groups='groups 2>/dev/null'\n" +
                        "# Resolve supplementary GIDs\n" +
                        "for gid in \$(id -G 2>/dev/null); do\n" +
                        "  grep -q \":\$gid:\" /etc/group 2>/dev/null || echo \"g\$gid:x:\$gid:\" >> /etc/group\n" +
                        "done\n" +
                        "# Boot cleanup\n" +
                        "apt-get clean -qq 2>/dev/null\n" +
                        "rm -rf /data/* /data/.* 2>/dev/null\n" +
                        "export HISTFILESIZE=100\n" +
                        "export HISTSIZE=100\n" +
                        "export PS1='\\[\\e[32m\\]\\u@\\h\\[\\e[0m\\]:\\[\\e[34m\\]\\w\\[\\e[0m\\]\\\\$ '\n" +
                        "export LANG=C.UTF-8\n" +
                        "alias ls='ls --color=auto'\n" +
                        "alias grep='grep --color=auto'\n" +
                        "# Ensure curl\n" +
                        "command -v curl >/dev/null 2>&1 && [ -f /etc/ssl/certs/ca-certificates.crt ] || (dpkg --configure -a 2>/dev/null; apt-get update -qq 2>/dev/null; apt-get install -y -qq curl ca-certificates tar 2>/dev/null; update-ca-certificates -f 2>/dev/null)\n" +
                        "# Auto-launch\n" +
                        cmdLine
                    bashrc.writeText(content)
                    bashrcState.writeText(effectiveCmd)
                }

                // Clean old /tmp inside rootfs (now bound to wsTmp)
                File(lxRoot, "tmp").let {
                    if (it.exists()) it.deleteRecursively()
                    it.mkdirs()
                }
                // Clean data/ accumulated inside rootfs (real dir only, skip symlink to host /data)
                File(lxRoot, "data").let {
                    if (it.exists() && !isSymlink(it)) it.deleteRecursively()
                    it.mkdirs()
                }


                val groupsWrapperId = File(lxRoot, "usr/local/bin/id")
                if (!groupsWrapperId.exists()) {
                    groupsWrapperId.parentFile?.mkdirs()
                    groupsWrapperId.writeText("""#!/bin/bash
exec /usr/bin/id "$@" 2>/dev/null
""")
                    groupsWrapperId.setExecutable(true)
                }
                val groupsWrapperGrp = File(lxRoot, "usr/local/bin/groups")
                if (!groupsWrapperGrp.exists()) {
                    groupsWrapperGrp.parentFile?.mkdirs()
                    groupsWrapperGrp.writeText("""#!/bin/bash
exec /usr/bin/groups "$@" 2>/dev/null
""")
                    groupsWrapperGrp.setExecutable(true)
                }

                val profileD = File(lxRoot, "etc/profile.d/00-fix-groups.sh")
                if (!profileD.exists()) {
                    profileD.parentFile?.mkdirs()
                    profileD.writeText(
                        "# Fix groups v2\nalias id='id 2>/dev/null'\nalias groups='groups 2>/dev/null'\n" +
                        "for gid in \$(id -G 2>/dev/null); do\n  grep -q \":\$gid:\" /etc/group 2>/dev/null || echo \"g\$gid:x:\$gid:\" >> /etc/group\ndone\n"
                    )
                    profileD.setExecutable(true)
                }

                terminalManager.setupWrappers()

                val aptSources = File(lxRoot, "etc/apt/sources.list")
                if (!aptSources.exists() || aptSources.readText().isBlank()) {
                    val codename = resolveDistroCodename(lxRoot)
                    aptSources.parentFile?.mkdirs()
                    aptSources.writeText(
                        "deb http://deb.debian.org/debian $codename main\n" +
                        "deb http://deb.debian.org/debian $codename-updates main\n" +
                        "deb http://deb.debian.org/debian-security $codename-security main\n"
                    )
                }

                val groupFile = File(lxRoot, "etc/group")
                if (!groupFile.exists()) {
                    groupFile.parentFile?.mkdirs()
                    groupFile.writeText(
                        "root:x:0:\ndaemon:x:1:\nbin:x:2:\nsys:x:3:\nadm:x:4:\ntty:x:5:\ndisk:x:6:\nlp:x:7:\nmail:x:8:\nnews:x:9:\nuucp:x:10:\nman:x:12:\nproxy:x:13:\nkmem:x:15:\ndialout:x:20:\nfax:x:21:\nvoice:x:22:\ncdrom:x:24:\nfloppy:x:25:\ntape:x:26:\nsudo:x:27:\nauditor:x:28:\nvideo:x:44:\nsaslauth:x:45:\nplugdev:x:46:\ngames:x:50:\ngopher:x:51:\nusers:x:100:\nnogroup:x:65534:\ninet:x:3003:\nnet_bt_admin:x:3005:\nnet_bt:x:3006:\nnet_bw_stats:x:3009:\nnet_bw_acct:x:3010:\neverybody:x:9997:\n"
                    )
                }

                syncDnsToRootfs()
                setSplashStatus(splash, getString(R.string.splash_deps))
                val curlBin = File(lxRoot, "usr/bin/curl")
                if (!curlBin.exists() || !File(lxRoot, "etc/ssl/certs/ca-certificates.crt").exists()) {
                    val offlineCopy = runCatching {
                        if (!curlBin.exists()) {
                            assets.open("curl_aarch64").use { input ->
                                curlBin.parentFile?.mkdirs()
                                curlBin.outputStream().use { input.copyTo(it) }
                            }
                            curlBin.setExecutable(true, false)
                        }
                        val caTarget = File(lxRoot, "etc/ssl/certs/ca-certificates.crt")
                        if (!caTarget.exists()) {
                            assets.open("cacert.pem").use { input ->
                                caTarget.parentFile?.mkdirs()
                                caTarget.outputStream().use { input.copyTo(it) }
                            }
                        }
                        curlBin.exists()
                    }.getOrDefault(false)
                    if (!offlineCopy) {
                        runInProot("dpkg --configure -a 2>/dev/null; apt-get update -qq 2>/dev/null; apt-get install -y -qq curl ca-certificates tar 2>/dev/null; update-ca-certificates -f 2>/dev/null", 300)
                    }
                }
                val prootBin = File(applicationInfo.nativeLibraryDir, "libproot_exec.so")
                val loader = File(applicationInfo.nativeLibraryDir, "libproot_loader.so")

                setSplashStatus(splash, getString(R.string.splash_starting))
                val shellPath = findShellInRootfs()
                if (shellPath == null) {
                    withContext(Dispatchers.Main) { statusText.text = getString(R.string.shell_not_found) }
                    return@launch
                }

                TermlouDirs.base(this@MainActivity).mkdirs()
                val args = mutableListOf(
                    "--root-id", "--link2symlink", "--kill-on-exit",
                    "-r", lxRoot.absolutePath,
                    "-w", "/workspace",
                    "-b", "${wsFiles.absolutePath}:/workspace",
                    "-b", "${wsTmp.absolutePath}:/tmp",
                    "-b", "${TermlouDirs.base(this@MainActivity).absolutePath}:/termlou",
                )
                for (p in listOf("/dev", "/proc", "/sys", "/etc/hosts")) {
                    if (File(p).exists()) args += listOf("-b", p)
                }
                args += shellPath

                val env = arrayOf(
                    "PROOT_LOADER=${loader.absolutePath}",
                    "PROOT_TMP_DIR=${wsTmp.absolutePath}",
                    "TMPDIR=/tmp",
                    "BUN_INSTALL_CACHE_DIR=/tmp/bun-cache",
                    "HOME=/root",
                    "PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "TERM=xterm-256color",
                    "LANG=C.UTF-8",
                    "LC_ALL=C.UTF-8",
                )

                withContext(Dispatchers.Main) {
                    val newSession = TerminalSession(
                        prootBin.absolutePath,
                        wsFiles.absolutePath,
                        args.toTypedArray(),
                        env,
                        null,
                        this@MainActivity
                    )
                    terminalManager.setSession(newSession)
                    terminalView.attachSession(newSession)
                    showTerminalView()
                    val remaining = maxOf(0L, 800L - (System.currentTimeMillis() - splashStartTime))
                    val tileAction = Runnable {
                        pendingTileCommand?.let { cmd ->
                            pendingTileCommand = null
                            val data = (cmd + "\n").toByteArray()
                            newSession.write(data, 0, data.size)
                        }
                    }
                    if (remaining > 0) {
                        splash.postDelayed({
                            splash.dismiss { rootLayout.removeView(splash) }
                            splash.postDelayed(tileAction, 200)
                        }, remaining)
                    } else {
                        splash.dismiss { rootLayout.removeView(splash) }
                        splash.postDelayed(tileAction, 200)
                    }
                }
            } catch (e: Exception) {
                Log.e("startShell", "shell start failed", e)
                withContext(Dispatchers.Main) {
                    rootLayout.removeView(splash)
                    statusText.text = getString(R.string.shell_error_fmt, e.message.toString())
                }
            }
        }
    }

    private fun runInProot(command: String, timeoutSec: Long): String = terminalManager.runInProot(command, timeoutSec)

    private fun notifyPreviousCrash() {
        runCatching {
            if (!::rootLayout.isInitialized) return
            val crashFile = File(filesDir, "crash.log")
            if (!crashFile.exists()) return
            val seenKey = "crashToast_${crashFile.lastModified()}"
            if (prefs.getBoolean(seenKey, false)) return
            prefs.edit().putBoolean(seenKey, true).apply()
            val text = runCatching { crashFile.readText() }.getOrDefault("")
            val cause = (application as TermLouApp).rootCauseLine(text)
            val show = { Snackbar.make(rootLayout, getString(R.string.crash_snack_fmt, cause.take(160)), Snackbar.LENGTH_LONG).show() }
            if (rootLayout.isAttachedToWindow) {
                show()
            } else {
                rootLayout.post { if (rootLayout.isAttachedToWindow) show() }
            }
            if (text.isNotBlank()) {
                runCatching {
                    val clip = android.content.ClipData.newPlainText("TermLou crash", text)
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                }
            }
        }
    }

    private fun loadThemeColors() {
        theme = ThemeColors.default()
        cSurface = theme.surface
        cSurfaceVariant = theme.surfaceVariant
        cPrimaryContainer = theme.primaryContainer
        cOutline = theme.outline
        cOnSurface = theme.onSurface
        cOnSurfaceVariant = theme.onSurfaceVariant
        cPrimary = theme.primary
        cError = theme.error
        cTertiary = theme.tertiary
    }

    private fun findShellInRootfs(): String? = terminalManager.findShellInRootfs()

    private fun isSymlink(file: File) = java.nio.file.Files.isSymbolicLink(file.toPath())

    override fun onDestroy() {
        if (isFinishing) {
            stopService(Intent(this, TermKeepAliveService::class.java))
        }
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        netFlowListener?.let { FlowLog.unsubscribe(it) }
        netRulesListener?.let { BlockRules.unsubscribe(it) }
        OverlayBridge.release()
        ClipboardBridge.release()
        terminalManager.setSession(null)
        terminalManager.destroy()
    }


    // TerminalSessionClient

    override fun onTextChanged(changedSession: TerminalSession?) {
        terminalView.onScreenUpdated()
        if (keepAlive) KeepAliveWakeLock.poke()
    }

    override fun onTitleChanged(changedSession: TerminalSession?) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession?) = Unit

    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
        val clip = android.content.ClipData.newPlainText("terminal", text)
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
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


    // TerminalViewClient

    override fun onScale(scale: Float): Float = scale.coerceIn(0.5f, 2.0f)
    override fun onSingleTapUp(e: MotionEvent?) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val keyboardVisible = Build.VERSION.SDK_INT >= 30 && window.decorView.rootWindowInsets
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
        if (terminalManager.handleCtrlKey(codePoint)) {
            setCtrlArmed(false, null, 0)
            return true
        }
        return false
    }
    override fun onEmulatorSet() = Unit
}

private class TickSlider(context: Context) : Slider(context) {
    private val activeTick = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inactiveTick = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickRadius = 3f * context.resources.displayMetrics.density

    fun setTickColors(active: Int, inactive: Int) {
        activeTick.color = active
        inactiveTick.color = inactive
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (trackWidth <= 0) return
        val count = (valueTo - valueFrom).toInt() + 1
        if (count < 2) return
        val start = trackSidePadding.toFloat()
        val spacing = trackWidth.toFloat() / (count - 1)
        val cy = height / 2f
        repeat(count) { i ->
            val x = start + i * spacing
            canvas.drawCircle(x, cy, tickRadius, if (valueFrom + i <= value) activeTick else inactiveTick)
        }
    }
}


data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    val system: Boolean
)
