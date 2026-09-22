package com.workspace.proot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 薄宿主 Activity：只负责装配顺序、四个 ActivityResultLauncher、
 * Tab 容器切换、生命周期扇出。业务逻辑分布在各 Controller。
 */
class MainActivity : AppCompatActivity() {

    internal lateinit var scope: AppScope
    private lateinit var statusController: StatusController
    internal lateinit var terminalController: TerminalController
    internal lateinit var workspaceController: WorkspaceController
    internal lateinit var networkController: NetworkController
    private lateinit var lanController: LanController
    internal lateinit var overlayCommands: OverlayCommandsController
    private lateinit var settingsUiController: SettingsUiController

    internal lateinit var rootLayout: FrameLayout
    private lateinit var slideContainer: FrameLayout
    private lateinit var tabHost: FrameLayout
    private lateinit var tabIndicator: View
    private lateinit var terminalTab: ImageView
    private lateinit var filesTab: ImageView
    private lateinit var notesTab: ImageView
    private lateinit var networkTab: ImageView
    private lateinit var settingsTab: ImageView
    private lateinit var terminalArea: LinearLayout
    private lateinit var filesArea: LinearLayout
    private lateinit var notesArea: LinearLayout
    private lateinit var networkArea: LinearLayout
    private lateinit var settingsWrapper: LinearLayout
    private lateinit var notesController: NotesController
    private lateinit var tabIntroController: TabIntroController

    internal var currentTab = 0
    private var animating = false

    /** 当前已显示（或动画目标）的 Tab 下标：切换动画的唯一真值，不再依赖 visibility 判断
     *  （250ms 内连点时两个视图都 VISIBLE，按 visibility 判断会错位）。 */
    private var displayedTab = 0
    private var animShow: View? = null
    private var animHide: View? = null
    private var animGen = 0

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        workspaceController.importFiles(uris)
    }

    private val importFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { workspaceController.importFolder(it) } }

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        networkController.onVpnPrepareResult(result.resultCode == RESULT_OK)
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        settingsUiController.onNotifPermResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scope = AppScope(
            prefs = getSharedPreferences("term-lou-settings", MODE_PRIVATE),
            lxRoot = java.io.File(filesDir, "workspace/linux"),
            wsFiles = java.io.File(filesDir, "workspace"),
            wsTmp = java.io.File(filesDir, "workspace/tmp")
        )
        TerminalManager.pruneStaleSessionTmp(scope.wsTmp)
        loadThemeColors()
        statusController = StatusController(this, scope)
        loadSettings()

        terminalController = TerminalController(this, scope, statusController, lifecycleScope) {
            overlayCommands.openShortcutSettings()
        }
        workspaceController = WorkspaceController(
            this, scope, statusController,
            pickFiles = { importLauncher.launch(arrayOf("*/*")) },
            pickFolder = { importFolderLauncher.launch(null) }
        )
        networkController = NetworkController(this, scope, statusController) {
            val i = VpnService.prepare(this)
            if (i != null) vpnPrepareLauncher.launch(i)
            else networkController.onVpnPrepareResult(true)
        }
        lanController = LanController(this, scope, statusController)
        overlayCommands = OverlayCommandsController(this, scope, statusController)
        settingsUiController = SettingsUiController(
            this, scope, statusController, terminalController, lanController, overlayCommands
        ) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        notesController = NotesController(this, scope, statusController)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::tabIntroController.isInitialized && tabIntroController.close()) return
                if (!notesController.handleBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "term-lou-keepalive", "TermLou", NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.perm_bg) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        if (!scope.prefs.getBoolean("fgServiceWarmed", false)) {
            // 预热前台服务：停止与标记改由服务自身负责。原先 Activity 里的 300ms postDelayed
            // 会被 onDestroy 的 removeCallbacksAndMessages(null) 清掉，导致服务 + wakelock 泄漏。
            ContextCompat.startForegroundService(this, Intent(this, TermKeepAliveService::class.java))
        }
        OverlayBridge.acquire(this, TermlouDirs.base(this))
        ClipboardBridge.acquire(this, TermlouDirs.base(this))
        TermlouDirs.migrateFromWorkspace(this, scope.wsFiles)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(scope.cSurface)
        }

        val statusView = statusController.createStatusBar()

        val (newTabHost, newTabIndicator) = scope.uiBuilder.createTabHost()
        tabHost = newTabHost
        tabIndicator = newTabIndicator

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
        }
        val tabViews = scope.uiBuilder.createTabBar()
        terminalTab = tabViews[0]
        notesTab = tabViews[1]
        filesTab = tabViews[2]
        networkTab = tabViews[3]
        settingsTab = tabViews[4]
        for (tv in tabViews) toolbar.addView(tv)
        tabViews.forEachIndexed { i, iv ->
            iv.setColorFilter(if (i == currentTab) scope.cOnSurface else scope.cOnSurfaceVariant)
        }
        tabHost.addView(toolbar)

        terminalArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        terminalController.buildInto(terminalArea)

        filesArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        workspaceController.buildInto(filesArea)

        notesArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        notesController.buildInto(notesArea)

        networkArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        networkController.buildInto(networkArea)

        settingsWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        settingsUiController.buildInto(settingsWrapper)

        content.addView(statusView)
        content.addView(tabHost)
        content.addView(terminalController.buildSetupArea())

        tabHost.post {
            val tabW = tabHost.width / 5
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
        slideContainer.addView(notesArea, FrameLayout.LayoutParams(
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
        statusController.showPreviousCrash(rootLayout)

        terminalArea.visibility = View.GONE
        filesArea.visibility = View.GONE
        networkArea.visibility = View.GONE
        settingsWrapper.visibility = View.GONE

        // Tab 介绍页：只挂主界面顶栏五个图标，不切换 Tab；子 Activity 无此 UI，天然不可触发。
        tabIntroController = TabIntroController(this, rootLayout, scope.theme, scope.wsFiles)
        tabIntroController.attach(listOf(terminalTab, notesTab, filesTab, networkTab, settingsTab))

        overlayCommands.handleNewIntent(intent, null, {}, {})
        terminalController.boot(rootLayout, intent.getStringExtra("tile_command"))

        if (scope.settingsManager.keepAlive && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, TermKeepAliveService::class.java))
        }
        workspaceController.handleShareIntent(intent)
        handleOpenNoteExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        workspaceController.handleShareIntent(intent)
        overlayCommands.handleNewIntent(
            intent,
            scope.terminalManager.session,
            { showTerminalView() },
            { cmd ->
                val data = (cmd + "\n").toByteArray()
                scope.terminalManager.session?.write(data, 0, data.size)
            }
        )
        handleOpenNoteExtra(intent)
    }

    private fun handleOpenNoteExtra(intent: Intent) {
        val name = intent.getStringExtra(NotesPageActivity.EXTRA_OPEN) ?: return
        showTab(1)
        notesController.openNote(name)
    }

    override fun onResume() {
        super.onResume()
        if (overlayCommands.consumeRefreshFlag()) terminalController.refreshAllRows()
        terminalController.onResume()
        networkController.onResume()
        settingsUiController.onResume()
        notesController.refresh()
        refreshStatusBar()
        NoteNotify.takeIfFresh()?.let { statusController.showTempStatus(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        tabHost.post {
            val tabW = tabHost.width / 5
            if (tabW > 0) {
                val lp = tabIndicator.layoutParams
                lp.width = tabW
                tabIndicator.layoutParams = lp
                tabIndicator.translationX = (currentTab * tabW).toFloat()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::notesController.isInitialized) notesController.flushPendingSave()
    }

    override fun onDestroy() {
        if (isFinishing) {
            stopService(Intent(this, TermKeepAliveService::class.java))
        }
        super.onDestroy()
        scope.mainHandler.removeCallbacksAndMessages(null)
        networkController.onDestroy()
        terminalController.onDestroy()
        statusController.onDestroy()
        notesController.onDestroy()
        if (::tabIntroController.isInitialized) tabIntroController.onDestroy()
        OverlayBridge.release()
        ClipboardBridge.release()
    }

    internal fun showTab(tabIndex: Int) {
        if (tabIndex != 0) hideIme()
        terminalController.hideSetup()
        val prev = currentTab
        currentTab = tabIndex
        if (prev == 1 && tabIndex != 1) notesController.flushPendingSave()

        val tabs = listOf(terminalTab, notesTab, filesTab, networkTab, settingsTab)
        val views = listOf(terminalArea, notesArea, filesArea, networkArea, settingsWrapper)

        for (i in tabs.indices) {
            tabs[i].setColorFilter(if (i == tabIndex) scope.cOnSurface else scope.cOnSurfaceVariant)
        }
        if (tabIndex != prev) {
            tabs[tabIndex].animate().cancel()
            tabs[tabIndex].alpha = 0f
            tabs[tabIndex].animate().alpha(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        } else {
            tabs[tabIndex].alpha = 1f
        }
        animateTabIndicator(tabIndex)
        statusController.bumpGen()
        refreshStatusBar()

        val showView = views[tabIndex]
        if (tabIndex != displayedTab) {
            animateTo(showView, views[displayedTab], tabIndex > displayedTab)
            displayedTab = tabIndex
        } else if (!animating && showView.visibility != View.VISIBLE) {
            // 兜底：无在途动画却与逻辑态不符（异常路径），直接对齐
            for (i in views.indices) {
                views[i].visibility = if (i == tabIndex) View.VISIBLE else View.GONE
                views[i].translationX = 0f
            }
        }

        if (tabIndex == 0) terminalController.terminalView.requestFocus()
        if (tabIndex == 1) notesController.refresh()
        if (tabIndex == 2) workspaceController.refreshFileList()
        if (tabIndex == 3) networkController.refreshNetTab()
    }

    internal fun showTerminalView() {
        showTab(0)
    }

    internal fun showFilesView() {
        showTab(2)
    }

    internal fun showNotesView() {
        showTab(1)
    }

    internal fun isSetupVisible(): Boolean = terminalController.isSetupVisible()

    private fun animateTabIndicator(tabIndex: Int) {
        val tabW = tabHost.width / 5
        if (tabW <= 0) return
        tabIndicator.animate()
            .translationX((tabIndex * tabW).toFloat())
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateTo(show: View, hide: View, fromRight: Boolean) {
        // 新一次切换先终结在途的那一次：取消动画并复位其视图，避免残影/串位
        finalizeTabAnim()
        val w = slideContainer.width
        if (w <= 0) {
            show.translationX = 0f
            hide.translationX = 0f
            show.visibility = View.VISIBLE
            hide.visibility = View.GONE
            return
        }
        animating = true
        animShow = show
        animHide = hide
        val gen = ++animGen

        val dir = if (fromRight) 1f else -1f
        show.translationX = dir * w
        show.visibility = View.VISIBLE
        show.bringToFront()

        hide.animate().translationX(-dir * w).setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()
        show.animate().translationX(0f).setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // 过期动画（被 cancel 时 endAction 仍可能触发）直接忽略，只让最新一次收尾
                if (gen != animGen) return@withEndAction
                hide.translationX = 0f
                hide.visibility = View.GONE
                show.translationX = 0f
                animating = false
                animShow = null
                animHide = null
            }
            .start()
    }

    /** 终结在途切换：无论 endAction 是否随 cancel 触发，都由这里统一复位，保证逻辑态与视图一致。 */
    private fun finalizeTabAnim() {
        val s = animShow
        val h = animHide
        animGen++ // 让在途 endAction 全部失效
        s?.animate()?.cancel()
        h?.animate()?.cancel()
        if (h != null) {
            h.translationX = 0f
            h.visibility = View.GONE
        }
        s?.translationX = 0f
        animShow = null
        animHide = null
        animating = false
    }

    internal fun refreshStatusBar() {
        if (isFinishing || isDestroyed) return
        statusController.setStatusText(when (currentTab) {
            1 -> "Notes"
            2 -> {
                val p = workspaceController.getRelativePath()
                if (p.isEmpty()) "Files" else "Files | $p"
            }
            3 -> networkController.netStatusLine()
            4 -> "Settings"
            else -> statusController.terminalBaseText()
        })
    }

    internal fun hideIme() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val token = currentFocus?.windowToken ?: rootLayout.windowToken
        if (token != null) imm.hideSoftInputFromWindow(token, 0)
    }

    private fun loadThemeColors() {
        // 根因记录：此处不能用 SettingsManager(prefs).nightMode——新实例未调 load()，
        // 字段恒为默认值 true，夜间开关永远不生效。主题加载早于 loadSettings，只能直读落盘值。
        val night = scope.prefs.getBoolean("nightMode", true)
        scope.theme = ThemeColors.default(night)
        val t = scope.theme
        scope.cSurface = t.surface
        scope.cSurfaceVariant = t.surfaceVariant
        scope.cPrimaryContainer = t.primaryContainer
        scope.cOutline = t.outline
        scope.cOnSurface = t.onSurface
        scope.cOnSurfaceVariant = t.onSurfaceVariant
        scope.cPrimary = t.primary
        scope.cError = t.error
        scope.cTertiary = t.tertiary
        scope.cSecondaryContainer = t.secondaryContainer
        scope.cOnSecondaryContainer = t.onSecondaryContainer
    }

    private fun loadSettings() {
        scope.settingsManager = SettingsManager(scope.prefs)
        scope.settingsManager.load()
        scope.uiBuilder = UiBuilder(this, scope.theme)
        scope.terminalManager = TerminalManager(this, scope.lxRoot, scope.wsFiles, java.io.File(scope.wsTmp, "main"))
        scope.shortcutManager = ShortcutManager(
            this, scope.theme, scope.settingsManager,
            writeFn = { cmd ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val bytes = cmd.toByteArray()
                        scope.terminalManager.session?.write(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        Log.e("shortcut", "write failed", e)
                    }
                }
            },
            onCardUsed = { label, state, count -> statusController.onCardUsed(label, state, count) },
            onCommandExecuted = { terminalController.wheelController?.hide() }
        )
        scope.fileListManager = FileListManager(this, scope.theme)
        scope.uiBuilder = UiBuilder(this, scope.theme)
    }

    }

internal class TickSlider(context: android.content.Context) : com.google.android.material.slider.Slider(context) {
    private val activeTick = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val inactiveTick = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val tickRadius = 2f * context.resources.displayMetrics.density

    fun setTickColors(active: Int, inactive: Int) {
        activeTick.color = active
        inactiveTick.color = inactive
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
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
    val icon: android.graphics.drawable.Drawable?,
    val system: Boolean
)
