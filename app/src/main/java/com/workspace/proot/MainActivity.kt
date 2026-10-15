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
    private lateinit var webAutomationController: WebAutomationController
    internal lateinit var overlayCommands: OverlayCommandsController
    private lateinit var settingsUiController: SettingsUiController

    internal lateinit var rootLayout: FrameLayout
    private lateinit var slidePager: androidx.viewpager2.widget.ViewPager2
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

    // 5.9.53：原先这一组手写切换动画的状态（animating / displayedTab / animShow /
    // animHide / animGen）随 ViewPager2 接入整体删除。页面显隐、translationX、
    // 动画收尾、连点时的在途取消，全部由 ViewPager2 内部负责，
    // 这里不再需要第二套真值 —— 那正是旧实现容易错位的原因（250ms 内连点时
    // 两个视图都 VISIBLE，靠 visibility 判断必然错位）。
    //
    // `currentTab` 保留：它是"逻辑态"，被各 Controller 读取
    // （`if (activity.currentTab == 3 && ...)`），且由 onPageSelected 统一维护。

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

        // 5.9.57：向系统请求 120Hz。自动模式下系统会参考 app 的偏好刷新率来选档。
        // 这只是一条**建议**（API 21+ 的 window 属性），系统可以驳回（省电/发热），
        // 设备不支持 120 会自动落到 60，不会异常。只加在主界面 —— 终端和全部动画都在这里，
        // 设置页/笔记页是静态画面，让它们留在低刷反而省电。
        window.attributes = window.attributes.apply { preferredRefreshRate = 120f }

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
        // 换色重建（ThemeSwap）：取走暂存的存活会话与 Tab；null = 冷启动路径。
        val swapRestore = ThemeSwap.takeRestore()

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
        webAutomationController = WebAutomationController(this, scope, statusController)
        overlayCommands = OverlayCommandsController(this, scope, statusController)
        settingsUiController = SettingsUiController(
            this, scope, statusController, terminalController, lanController,
            webAutomationController, overlayCommands
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

        val (newTabHost, newTabIndicator) = scope.uiBuilder.createTabHost()
        tabHost = newTabHost
        tabIndicator = newTabIndicator

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_1, UiTokens.SPACE_0, UiTokens.SPACE_0)
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

        content.addView(statusController.createStatusWrap())
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

        slidePager = androidx.viewpager2.widget.ViewPager2(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            // 五个页面全程保持存活：终端会话、笔记编辑态、文件列表都是"建好就不重建"的，
            // 丢一个 holder 就等于丢用户状态。offscreenPageLimit 拉到 5 让 RecyclerView
            // 把五个 item 全部常驻，不回收。
            offscreenPageLimit = 5
            adapter = TabPagerAdapter()
            registerOnPageChangeCallback(pageCallback)
            // 5.9.54：**这里原本有一行"按 setup 是否可见来开关横滑"的赋值，已删除。**
            // 它是 5.9.53 引入的回归根因：此刻 buildSetupArea() 刚建完 setupArea，
            // 而 LinearLayout 默认 VISIBLE、boot() 还没跑 —— 于是读到 true 把横滑关掉，
            // 之后 boot() 隐藏了 setupArea 却没人重新求值，横滑就永久死了
            // （点一下 tab 图标碰巧能恢复，所以现象是"划不动、点一下就好了"）。
            // 现在改为由 terminalController 的 setup 显隐通知驱动，见 applySetupGuard。
            //
            // ⚠ 这几行刻意不写出被删掉那个属性的名字：UiDimsScanTest 里新加的锁
            // 正是按字面扫本构造块，而 `code()` 只过滤 `*`/`/*` 开头的行、不过滤 `//`，
            // 注释里出现会让锁自己绊倒（同一个坑本项目已栽到第四次）。
        }
        terminalController.onSetupVisibilityChanged = { applySetupGuard(it) }
        content.addView(slidePager)

        rootLayout = FrameLayout(this)
        rootLayout.addView(content)
        setContentView(rootLayout)
        statusController.showPreviousCrash(rootLayout)

        // 5.9.53：原先这里把五个视图全设 GONE、并在 showTab 里手工切 visibility。
        // ViewPager2 自己管理 item 的挂载与显隐，不再需要 —— 手工设 GONE 反而会让
        // RecyclerView 认为 item 不可见。

        // Tab 介绍页：只挂主界面顶栏五个图标，不切换 Tab；子 Activity 无此 UI，天然不可触发。
        tabIntroController = TabIntroController(this, rootLayout, scope.theme, scope.wsFiles)
        tabIntroController.attach(listOf(terminalTab, notesTab, filesTab, networkTab, settingsTab))

        if (swapRestore == null) {
            overlayCommands.handleNewIntent(intent, null, {}, {})
            terminalController.boot(rootLayout, intent.getStringExtra("tile_command"))
        } else {
            // 换色重建：回挂存活会话（不播开屏、不重启 shell）；失败兜底整进程重启，
            // ThemeSwap 已消费清空，重启后走全新冷启动，保证不残废。
            runCatching { terminalController.restoreSession(swapRestore.session) }
                .onFailure { e ->
                    Log.e("MainActivity", "swap restore failed", e)
                    restartApp()
                }
        }

        if (scope.settingsManager.keepAlive && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, TermKeepAliveService::class.java))
        }
        if (swapRestore == null) {
            workspaceController.handleShareIntent(intent)
            handleOpenNoteExtra(intent)
        } else {
            // 回到用户离开前所在的 Tab（冷启动由开屏结束的 showTerminalView 归位）。
            showTab(swapRestore.tab)
        }
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

    /**
     * 换色重建回挂失败的兜底：先递 launcher intent 再自杀，系统会拉起新进程
     * （同语言/夜间开关的历史重启路径；ThemeSwap 已消费清空，重启即全新冷启动）。
     */
    private fun restartApp() {
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (launch != null) startActivity(launch)
        }
        kotlin.system.exitProcess(0)
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
        // 调色盘保存/恢复默认：落盘后回本界面即刻换色——重建视图换新主题，
        // 会话与滚动缓冲保留（旧实例 onDestroy 见 ThemeSwap.hasRestore() 不杀 shell）。
        if (ThemeSwap.takeMarked(scope.terminalManager.session, currentTab)) recreate()
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
        terminalController.onPause()
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

    /**
     * 5.9.53：**请求**切到某个页签。
     *
     * 只做两件事：前置的收尾动作 + 让 ViewPager2 切过去。
     * 页面显隐与滑动动画由 ViewPager2 负责，所以这里**不再**碰 translationX/visibility。
     *
     * 状态更新（图标颜色、指示条、状态栏特征字、各页刷新）全部收敛到
     * [pageCallback] 的 `onPageSelected` —— 那才是"页签真的变了"的唯一时刻。
     * 这样手指拖动和代码调用两条路径共用同一套收尾，不会各写一份而错位。
     */
    internal fun showTab(tabIndex: Int) {
        if (tabIndex !in 0..4) return
        if (tabIndex != 0) hideIme()
        terminalController.hideSetup()
        val prev = currentTab
        if (prev == 1 && tabIndex != 1) notesController.flushPendingSave()

        if (slidePager.currentItem != tabIndex) {
            // 用户正在拖动时不要抢方向盘：交给 ViewPager2 自己结算，
            // 否则会把"跟着手指"打断成"跳过去"。
            if (slidePager.isFakeDragging) return
            // 首次布局之前没有可动画的几何，setCurrentItem(…, true) 会闪一下；
            // 这种情况直接落位。
            slidePager.setCurrentItem(tabIndex, slidePager.width > 0)
        } else if (tabIndex != prev) {
            // 同一页被重复请求（例如换色重建后直接 showTab(当前页)）：逻辑态已经对上了，
            // 但仍要把状态刷新一遍，避免图标颜色/状态栏停在旧值。
            applyTabState(tabIndex, prev)
        }
    }

    /**
     * 页签真的变了：图标、指示条、状态栏、各页刷新 —— 唯一的状态真值。
     *
     * 拖动松手会回到原页，这时 [prev] 是拖动中途选中的那个新页，
     * 方向仍由 `tabIndex > prev` 得出，与旧实现同一套判断。
     */
    /**
     * setup 区可见时不接受横滑切页（setup 只在终端页有意义）。
     *
     * 5.9.54：**唯一**改这个开关的地方。[visible] 传 null 表示"还没求过值"，
     * 此时也要求值一次 —— 宁可多算一遍，也不留"构造期读未定义状态"那种坑。
     */
    private fun applySetupGuard(visible: Boolean? = terminalController.isSetupVisible()) {
        slidePager.isUserInputEnabled = visible != true
    }

    private fun applyTabState(tabIndex: Int, prev: Int) {
        applySetupGuard()
        val tabs = listOf(terminalTab, notesTab, filesTab, networkTab, settingsTab)
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
        // 5.9.48：告诉状态栏特征字立方体的转向 —— 必须与页面滑动方向一致。
        // 此刻 [prev] 还是**旧**值，正好能算出方向：
        // 切到后面的页签（tabIndex > prev）时内容向左滑、新页从右边进来，
        // 所以立方体也从右侧转出。
        statusController.setSweepDirection(toLeft = tabIndex > prev)
        refreshStatusBar()

        if (tabIndex == 0) terminalController.terminalView.requestFocus()
        if (tabIndex == 1) notesController.refresh()
        if (tabIndex == 2) workspaceController.refreshFileList()
        if (tabIndex == 3) networkController.refreshNetTab()
    }

    /** ViewPager2 的唯一状态出口：拖动与代码调用都从这里汇合。 */
    private val pageCallback = object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
        /**
         * 5.9.54：把 `if (position == prev) return` 从"直接返回"改成"仍跑一遍状态同步"。
         *
         * 那句提前返回正是上一轮横滑死掉的共犯：启动时 ViewPager2 会回调
         * `onPageSelected(0)`，而 `currentTab` 本来就是 0 → 直接 return，
         * 于是 `applyTabState` 从没被执行过 —— 里面那句"重新求值 setup 守卫"也就没机会跑。
         *
         * 现在只在 `currentTab` 已经是目标值时**跳过赋值**，但仍然同步其余状态：
         * 首次回调正是一次免费的"把全部状态对齐到当前页"的机会，不该浪费。
         */
        override fun onPageSelected(position: Int) {
            val prev = currentTab
            currentTab = position
            applyTabState(position, prev)
        }
    }

    /**
     * 五个页面各自的 ViewHolder。
     *
     * 直接复用**已经建好的那五个 View**，不新建、不重建 —— 终端会话、笔记编辑态、
     * 文件列表都必须跨页签存活，RecyclerView 回收 holder 等于丢状态。
     */
    private inner class TabPagerAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = 5

        // 一页一个 viewType：保证五个 holder 互不复用，天然不会抢同一个 View
        override fun getItemViewType(position: Int) = position

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PageHolder {
            val v = when (viewType) {
                0 -> terminalArea
                1 -> notesArea
                2 -> filesArea
                3 -> networkArea
                else -> settingsWrapper
            }
            // 保险：万一还挂在别的 holder 上（换色重建等异常路径），先摘掉，
            // 否则 RecyclerView 会抛 "specified child already has a parent"。
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            v.layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT,
                androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT,
            )
            return PageHolder(v)
        }

        // View 是预先建好的，绑定时无需做任何事
        override fun onBindViewHolder(holder: PageHolder, position: Int) = Unit

        // 五个 View 由 Activity 持有，生命周期跟着 Activity，不随 holder 回收
        override fun onViewRecycled(holder: PageHolder) = Unit
    }

    private class PageHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

    internal fun showTerminalView() {
        showTab(0)
    }

    // 5.9.53：showFilesView / showNotesView 随旧的手势回调一起删除 ——
    // 它们原本只被那四个 TabSwipe* 容器的 onSwipeLeft/Right 调用，
    // 现在切页签由 ViewPager2 独占，全模块已无调用点。
    // 若以后需要"从别处跳到某一页"，直接 showTab(n) 即可，不必再包一层。

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

    // 5.9.53：原先这里的 animateTo / finalizeTabAnim 已随 ViewPager2 接入整体删除。
    // 它们手工管 translationX + visibility + 在途取消，为此还养了一组状态
    // （animating / animShow / animHide / animGen）作为"第二套真值" ——
    // 那正是旧实现需要靠 `animGen` 做失效判断来兜的复杂度来源。
    // ViewPager2 内部把这些（以及连点打断、拖动回弹、边界）都做完了，
    // 这里不需要再复述一遍，也就不该留着一份会走样的影子。

    internal fun refreshStatusBar() {
        if (isFinishing || isDestroyed) return
        // ⚠ 5.9.46：必须走 statusViewOrNull()，不能直接摸 statusView。
        // 它是 lateinit，要到本文件 213 行建状态栏外框时才赋值；而本方法
        // 被 onResume 调到，**首次启动时 onResume 也跑**，那条路径上它可能还没建 ——
        // 直接访问会抛 UninitializedPropertyAccessException → 静默杀进程 → **黑屏**。
        // 5.9.45 就是漏了这个保护，导致 App 打不开。状态栏还没建时本来就没什么可复位的。
        //
        // 5.9.47：状态栏拆两块后，这里管的是**块②**（内容区）的跑马灯状态。
        // 块① 特征字固定不动，不受影响。
        //
        // 5.9.48：**文件页不再被特殊跳过**。此前它跳过是因为"跑马灯只能由点文件的
        // 收尾动作开启"，跳过复位才不至于把刚开的动画掐掉 —— 那是本末倒置：
        // 结果是"切到文件页 / 切走再回来都不滚"（用户实机报的核心问题）。
        // 现在文件页走 setFilesPath()，它**自己就会开跑马灯**，所以这里照常复位即可，
        // 顺序变成"先清干净、再由 setFilesPath 按需开启"，逻辑单向了。
        //
        // （注：本段注释刻意不写出那个建外框的函数名 —— RegressionScanTest 有条老锁用
        //  正则数它在 MainActivity 里出现几次必须恰好是 1，注释里写会被一并数进去。）
        statusController.statusViewOrNull()?.let { StatusMarquee.clearPersistent(it) }
        if (currentTab == 2) {
            // 文件页：路径超长就立刻开滚（不必等用户点文件）
            statusController.setFilesPath(workspaceController.getRelativePath())
        } else {
            statusController.setStatusText(when (currentTab) {
                1 -> "Notes"
                3 -> networkController.netStatusLine()
                4 -> "Settings"
                else -> statusController.terminalBaseText()
            })
        }
        statusController.syncWebNotice()
    }

    internal fun hideIme() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val token = currentFocus?.windowToken ?: rootLayout.windowToken
        if (token != null) imm.hideSoftInputFromWindow(token, 0)
    }

    private fun loadThemeColors() {
        // 主题只认落盘值：PaletteStore.theme 直读 prefs（夜档 + 调色盘覆盖）。
        // 历史根因：经未 load() 的 SettingsManager 转手会读到默认值（夜间开关曾因此失效）。
        scope.theme = PaletteStore.theme(scope.prefs)
        val t = scope.theme
        scope.cSurface = t.surface
        scope.cSurfaceVariant = t.surfaceVariant
        scope.cPrimaryContainer = t.primaryContainer
        scope.cOutline = t.outline
        scope.cOnSurface = t.onSurface
        scope.cOnSurfaceVariant = t.onSurfaceVariant
        scope.cPrimary = t.primary
        scope.cPrimaryVisible = t.primaryVisible
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
