package com.workspace.proot

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * 网络域：VPN Tab 整块 UI、流量渲染/展开/屏蔽、抓包启停、日志弹窗。
 * 原 MainActivity 网络相关 ~300 行收归此处。
 */
class NetworkController(
    private val activity: MainActivity,
    private val scope: AppScope,
    private val status: StatusController,
    private val requestVpnPrepare: () -> Unit
) {
    private lateinit var networkInner: LinearLayout
    private lateinit var netMenuHost: FrameLayout
    private lateinit var netBraceMenu: BraceMenu
    private lateinit var netFlowList: LinearLayout
    private lateinit var netToggleBtn: Button

    private val netExpandedIds = HashSet<Long>()
    private var netFlowDirty = false
    private var lastNetStatusShown: String? = null
    private var netFlowListener: (() -> Unit)? = null
    private var netRulesListener: (() -> Unit)? = null
    private val netFlowRunnable = Runnable {
        if (netFlowDirty && activity.currentTab == 2) {
            netFlowDirty = false
            renderNetFlows()
        }
    }

    /** 网络 Tab 整块 UI（含底栏），挂到 networkArea 下。 */
    fun buildInto(networkArea: LinearLayout) {
        netMenuHost = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply {
                weight = 1f
            }
        }
        val networkScroll = TabSwipeScrollView(
            activity,
            onSwipeRight = { if (activity.currentTab == 2 && !activity.isSetupVisible()) activity.showFilesView() },
            onSwipeLeft = { if (activity.currentTab == 2 && !activity.isSetupVisible()) activity.showTab(3) }
        ).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        networkInner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        networkScroll.addView(networkInner)
        netMenuHost.addView(networkScroll)
        buildNetworkContent()
        networkArea.addView(netMenuHost)
        networkArea.addView(createNetBottomBar())

        netFlowListener = { scope.mainHandler.post { scheduleNetFlows() } }
        FlowLog.subscribe(netFlowListener!!)
        netRulesListener = { scope.mainHandler.post { scheduleNetFlows() } }
        BlockRules.subscribe(netRulesListener!!)
    }

    private fun buildNetworkContent() {
        netBraceMenu = BraceMenu(activity)
        netFlowList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (10 * activity.resources.displayMetrics.density).toInt(), 0, 0)
        }
        networkInner.addView(netFlowList)
    }

    private fun createNetBottomBar(): LinearLayout {
        fun barButton(text: String, color: Int, onClick: () -> Unit): Button = Button(activity).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            isAllCaps = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(3, 0, 3, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            ButtonStyle.apply(this, color)
            setOnClickListener { onClick() }
        }
        fun gridRow(vararg buttons: Button): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 2, 4, 2)
            for (b in buttons) addView(b)
        }
        netToggleBtn = barButton(activity.getString(R.string.net_capture_start), scope.cPrimary) {
            if (NetVpnService.isRunning) stopNet() else startNet()
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(scope.cSurface)
            addView(gridRow(
                netToggleBtn,
                barButton(activity.getString(R.string.net_pick_apps), scope.cOutline) {
                    NetAppPickerDialog(
                        activity, scope.cPrimary, scope.cOnSurfaceVariant,
                        activity.overlayCommands.loadAppCache(), scope.settingsManager,
                        { refreshNetTab() }, scope.theme
                    ).show()
                }
            ))
            addView(gridRow(
                barButton(activity.getString(R.string.net_clear), scope.cOutline) {
                    FlowLog.clear()
                    renderNetFlows()
                    status.showTempStatus(activity.getString(R.string.net_cleared))
                },
                barButton(activity.getString(R.string.net_view_log), scope.cOutline) { showNetLogDialog() }
            ))
        }
    }

    private fun showNetLogDialog() {
        val density = activity.resources.displayMetrics.density
        val log = NetVpnService.readLogTail(activity, 200)
        val startLog = runCatching {
            File(activity.filesDir, "net/start.log").readText()
        }.getOrDefault("")
        val miniLog = runCatching {
            val f = File(activity.filesDir, "net/minisocks.log")
            if (f.exists()) f.readLines().takeLast(60).joinToString("\n") else ""
        }.getOrDefault("")
        val content = buildString {
            if (startLog.isNotBlank()) { append("== start.log ==\n").append(startLog) }
            append(activity.getString(R.string.net_log_tail_tun))
            append(if (log.isBlank()) activity.getString(R.string.net_log_empty) else log)
            append(activity.getString(R.string.net_log_tail_mini))
            append(if (miniLog.isBlank()) activity.getString(R.string.net_log_empty) else miniLog)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.net_log_title))
            .setView(ScrollView(activity).apply {
                addView(TextView(activity).apply {
                    text = content
                    setTextColor(Color.WHITE)
                    textSize = UiTokens.TEXT_COMPACT
                    typeface = Typeface.MONOSPACE
                    setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
                })
            })
            .setPositiveButton(activity.getString(R.string.close), null)
            .create()
        DialogStyler.apply(dialog, scope.theme)
        dialog.show()
    }

    fun refreshNetTab() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (::netToggleBtn.isInitialized) {
            val running = NetVpnService.isRunning
            val hasApps = scope.settingsManager.loadCaptureApps().isNotEmpty()
            netToggleBtn.text = if (running) activity.getString(R.string.net_capture_stop) else activity.getString(R.string.net_capture_start)
            netToggleBtn.setTextColor(Color.WHITE)
            if (!running && !hasApps) {
                netToggleBtn.isEnabled = false
                netToggleBtn.alpha = 0.5f
                ButtonStyle.apply(netToggleBtn, scope.cOutline)
            } else {
                netToggleBtn.isEnabled = true
                netToggleBtn.alpha = 1f
                ButtonStyle.apply(netToggleBtn, if (running) scope.cError else scope.cPrimary)
            }
        }
        renderNetFlows()
        val statusText = NetVpnService.statusText.substringBefore('\n')
        if (!NetVpnService.isRunning && statusText.isNotEmpty() && statusText != activity.getString(R.string.vpn_idle)) {
            if (statusText != lastNetStatusShown) {
                lastNetStatusShown = statusText
                status.showTempStatus("Network | $statusText")
            }
        } else {
            lastNetStatusShown = null
        }
        activity.refreshStatusBar()
    }

    /** 状态栏网络行文案（供宿主 refreshStatusBar 读取）。 */
    fun netStatusLine(): String {
        val s = NetVpnService.statusText.substringBefore('\n')
        if (NetVpnService.isRunning) return "Network | $s"
        return if (scope.settingsManager.loadCaptureApps().isEmpty()) {
            activity.getString(R.string.net_no_apps)
        } else {
            "Network"
        }
    }

    private fun renderNetFlows() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!::netFlowList.isInitialized) return
        netFlowList.removeAllViews()
        val rows = FlowLog.list()
        if (rows.isEmpty()) {
            netFlowList.addView(TextView(activity).apply {
                text = activity.getString(R.string.net_empty)
                setTextColor(scope.cOnSurfaceVariant)
                textSize = UiTokens.TEXT_COMPACT
                setPadding(0, 8, 0, 8)
            })
            return
        }
        val density = activity.resources.displayMetrics.density
        val limit = minOf(rows.size, 80)
        for (i in 0 until limit) {
            val f = rows[i]
            netFlowList.addView(buildFlowRow(f))
            netFlowList.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(scope.cOutline)
            })
        }
    }

    private fun buildFlowRow(f: FlowEntry): View {
        val density = activity.resources.displayMetrics.density
        val ip = f.dstIp
        val domain = f.domain
        val blocked = f.state == "BLOCKED" || BlockRules.isBlocked(ip, domain)
        val main = if (!domain.isNullOrBlank()) domain else ip
        val mainColor = when {
            blocked -> Color.parseColor("#FF6E6E")
            f.proto == "UDP" -> Color.parseColor("#FFD54F")
            else -> UiTokens.statusGreen
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(if (blocked) UiTokens.dirRowBg else Color.TRANSPARENT)
            setOnClickListener { toggleFlowExpand(f.id) }
            setOnLongClickListener {
                showNetBraceMenu(this, f)
                true
            }
        }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = main
                setTextColor(mainColor)
                textSize = UiTokens.TEXT_BODY
                typeface = Typeface.MONOSPACE
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (blocked) {
                addView(TextView(activity).apply {
                    text = activity.getString(R.string.flow_blocked)
                    setTextColor(Color.parseColor("#FF5252"))
                    textSize = UiTokens.TEXT_META
                    typeface = Typeface.MONOSPACE
                    setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
                    setBackgroundColor(Color.parseColor("#26FF5252"))
                })
            }
        })
        row.addView(TextView(activity).apply {
            text = buildFlowInfo(f, domain)
            setTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            typeface = Typeface.MONOSPACE
            setPadding(0, (2 * density).toInt(), 0, 0)
        })
        if (f.id in netExpandedIds) {
            row.addView(TextView(activity).apply {
                text = buildFlowDetail(f, domain)
                setTextColor(scope.cOnSurfaceVariant)
                textSize = UiTokens.TEXT_META
                typeface = Typeface.MONOSPACE
                setPadding(0, (2 * density).toInt(), 0, 0)
            })
        }
        return row
    }

    private fun buildFlowInfo(f: FlowEntry, domain: String?): String {
        val stateCn = when (f.state) {
            "OPEN" -> activity.getString(R.string.flow_state_open)
            "UDP" -> activity.getString(R.string.flow_state_udp)
            "BLOCKED" -> activity.getString(R.string.flow_blocked)
            else -> activity.getString(R.string.flow_state_closed)
        }
        return "${f.proto}:${f.dstPort} · ↑${formatBytes(f.bytesUp)} ↓${formatBytes(f.bytesDown)} · $stateCn · ${f.time}"
    }

    private fun buildFlowDetail(f: FlowEntry, domain: String?): String {
        val detail = if (!domain.isNullOrBlank()) {
            activity.getString(R.string.flow_server_fmt, f.dstIp)
        } else {
            activity.getString(R.string.flow_no_domain)
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
                netMenuHost, ay, rh, activity.getString(R.string.flow_unblock), opt2Label,
                confirm = opt2Label != null,
                onOpt1 = {
                    if (matched != null) BlockRules.removeBlockDomain(matched)
                    else BlockRules.removeBlockIp(ip)
                    status.showTempStatus(activity.getString(R.string.flow_unblocked))
                },
                onOpt2 = {
                    if (opt2Label != null) BlockRules.addBlockDomain(opt2Label)
                    status.showTempStatus(activity.getString(R.string.flow_block_all_fmt, opt2Label))
                }
            )
        } else {
            val opt2Label = if (!domain.isNullOrBlank()) rootDomainOf(domain) else null
            netBraceMenu.show(
                netMenuHost, ay, rh, activity.getString(R.string.flow_block_ip), opt2Label,
                confirm = opt2Label != null,
                onOpt1 = {
                    BlockRules.addBlockIp(ip)
                    status.showTempStatus(activity.getString(R.string.flow_blocked_ip_fmt, ip))
                },
                onOpt2 = {
                    if (opt2Label != null) BlockRules.addBlockDomain(opt2Label)
                    status.showTempStatus(activity.getString(R.string.flow_block_all_fmt, opt2Label))
                }
            )
        }
    }

    private fun scheduleNetFlows() {
        netFlowDirty = true
        scope.mainHandler.removeCallbacks(netFlowRunnable)
        scope.mainHandler.postDelayed(netFlowRunnable, 400)
    }

    private fun startNet() {
        if (scope.settingsManager.loadCaptureApps().isEmpty()) {
            status.showTempStatus(activity.getString(R.string.net_pick_first))
            refreshNetTab()
            return
        }
        requestVpnPrepare()
    }

    fun onVpnPrepareResult(granted: Boolean) {
        if (granted) {
            NetVpnService.start(activity)
        } else {
            status.showTempStatus(activity.getString(R.string.vpn_auth_cancelled))
        }
        scope.mainHandler.postDelayed({ refreshNetTab() }, 1500)
    }

    private fun stopNet() {
        NetVpnService.stop(activity)
        status.showTempStatus(activity.getString(R.string.net_stopped))
        scope.mainHandler.postDelayed({ refreshNetTab() }, 500)
    }

    fun onResume() {
        if (activity.currentTab == 2) refreshNetTab()
    }

    fun onDestroy() {
        scope.mainHandler.removeCallbacks(netFlowRunnable)
        netFlowListener?.let { FlowLog.unsubscribe(it) }
        netRulesListener?.let { BlockRules.unsubscribe(it) }
        netFlowListener = null
        netRulesListener = null
    }
}
