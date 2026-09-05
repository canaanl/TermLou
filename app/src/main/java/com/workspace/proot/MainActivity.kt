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
    private lateinit var networkTab: ImageView
    private lateinit var settingsTab: ImageView
    private lateinit var terminalArea: LinearLayout
    private lateinit var filesArea: LinearLayout
    private lateinit var networkArea: LinearLayout
    private lateinit var settingsWrapper: LinearLayout

    internal var currentTab = 0
    private var animating = false

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
            this, scope, statusController, terminalController, lanController, overlayCommands,
            lifecycleScope
        ) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "term-lou-keepalive", "TermLou", NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.perm_bg) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        if (!scope.prefs.getBoolean("fgServiceWarmed", false)) {
            val warmIntent = Intent(this, TermKeepAliveService::class.java)
            ContextCompat.startForegroundService(this, warmIntent)
            scope.mainHandler.postDelayed({
                stopService(warmIntent)
                scope.prefs.edit().putBoolean("fgServiceWarmed", true).apply()
            }, 300)
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
        filesTab = tabViews[1]
        networkTab = tabViews[2]
        settingsTab = tabViews[3]
        for (tv in tabViews) toolbar.addView(tv)
        tabViews.forEachIndexed { i, iv ->
            iv.setColorFilter(if (i == currentTab) Color.WHITE else scope.cSurfaceVariant)
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
        statusController.showPreviousCrash(rootLayout)

        terminalArea.visibility = View.GONE
        filesArea.visibility = View.GONE
        networkArea.visibility = View.GONE
        settingsWrapper.visibility = View.GONE

        overlayCommands.handleNewIntent(intent, null, {}, {})
        terminalController.boot(rootLayout, intent.getStringExtra("tile_command"))

        if (scope.settingsManager.keepAlive && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, TermKeepAliveService::class.java))
        }
        workspaceController.handleShareIntent(intent)
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
    }

    override fun onResume() {
        super.onResume()
        if (overlayCommands.consumeRefreshFlag()) terminalController.refreshAllRows()
        terminalController.onResume()
        networkController.onResume()
        settingsUiController.onResume()
        refreshStatusBar()
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

    override fun onDestroy() {
        if (isFinishing) {
            stopService(Intent(this, TermKeepAliveService::class.java))
        }
        super.onDestroy()
        scope.mainHandler.removeCallbacksAndMessages(null)
        networkController.onDestroy()
        terminalController.onDestroy()
        statusController.onDestroy()
        OverlayBridge.release()
        ClipboardBridge.release()
    }

    internal fun showTab(tabIndex: Int) {
        if (tabIndex != 0) hideIme()
        terminalController.hideSetup()
        val prev = currentTab
        currentTab = tabIndex

        val tabs = listOf(terminalTab, filesTab, networkTab, settingsTab)
        val views = listOf(terminalArea, filesArea, networkArea, settingsWrapper)

        for (i in 0..3) {
            tabs[i].setColorFilter(if (i == tabIndex) Color.WHITE else scope.cSurfaceVariant)
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

        if (tabIndex == 0) terminalController.terminalView.requestFocus()
        if (tabIndex == 1) workspaceController.refreshFileList()
        if (tabIndex == 2) networkController.refreshNetTab()
    }

    internal fun showTerminalView() {
        showTab(0)
    }

    internal fun showFilesView() {
        showTab(1)
    }

    internal fun isSetupVisible(): Boolean = terminalController.isSetupVisible()

    private fun animateTabIndicator(tabIndex: Int) {
        val tabW = tabHost.width / 4
        if (tabW <= 0) return
        tabIndicator.animate()
            .translationX((tabIndex * tabW).toFloat())
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()
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

    internal fun refreshStatusBar() {
        if (isFinishing || isDestroyed) return
        statusController.setStatusText(when (currentTab) {
            1 -> {
                val p = workspaceController.getRelativePath()
                if (p.isEmpty()) "Files" else "Files | $p"
            }
            2 -> networkController.netStatusLine()
            3 -> "Settings"
            else -> statusController.terminalBaseText()
        })
    }

    internal fun hideIme() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val token = currentFocus?.windowToken ?: rootLayout.windowToken
        if (token != null) imm.hideSoftInputFromWindow(token, 0)
    }

    private fun loadThemeColors() {
        scope.theme = ThemeColors.default()
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
    }

    private fun loadSettings() {
        scope.settingsManager = SettingsManager(scope.prefs)
        scope.settingsManager.load()
        scope.uiBuilder = UiBuilder(this, scope.theme)
        scope.terminalManager = TerminalManager(this, scope.lxRoot, scope.wsFiles, scope.wsTmp)
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

    companion object {
        internal const val DIM = 0f
    }
}

internal class TickSlider(context: android.content.Context) : com.google.android.material.slider.Slider(context) {
    private val activeTick = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val inactiveTick = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val tickRadius = 3f * context.resources.displayMetrics.density

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
