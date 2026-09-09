package com.workspace.proot

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
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

    private lateinit var netDashboard: LinearLayout
    private lateinit var netSearch: EditText
    private lateinit var netStatsRate: TextView
    private lateinit var netStatsTotal: TextView
    private lateinit var netStatsCount: TextView
    private lateinit var netStackBar: StackedBarView
    private lateinit var netLegend: LinearLayout

    private val netExpandedIds = HashSet<Long>()
    private var netFlowDirty = false
    private var lastNetStatusShown: String? = null
    private var netFlowListener: (() -> Unit)? = null
    private var netRulesListener: (() -> Unit)? = null
    private var dnsListener: (() -> Unit)? = null
    private var netTickerPosted = false
    private var lastTickUp = 0L
    private var lastTickDown = 0L
    private var lastTickMs = 0L

    private val chartPalette = listOf(UiTokens.primaryGreen, UiTokens.amber, UiTokens.linkCyan, UiTokens.totalText)

    private val netFlowRunnable: Runnable = Runnable {
        if (netFlowDirty && activity.currentTab == 2) {
            netFlowDirty = false
            renderNetFlows()
        }
    }

    private val netTicker: Runnable = Runnable {
        netTickerPosted = false
        if (!NetVpnService.isRunning) return@Runnable
        if (activity.currentTab == 2) {
            renderDashboard(FlowLog.list())
            netTickerPosted = true
            scope.mainHandler.postDelayed(netTicker, 1000L)
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
        )
        networkInner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 32)
        }
        networkScroll.addView(networkInner)
        // 看板高度=内容，连接列表占剩余空间
        val netColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        netDashboard = buildDashboard()
        val dashDp = activity.resources.displayMetrics.density
        netColumn.addView(netDashboard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = (6 * dashDp).toInt()
            rightMargin = (6 * dashDp).toInt()
            topMargin = (6 * dashDp).toInt()
            bottomMargin = (6 * dashDp).toInt()
        })
        netColumn.addView(networkScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        netMenuHost.addView(netColumn)
        buildNetworkContent()
        networkArea.addView(netMenuHost)
        networkArea.addView(createNetBottomBar())

        netFlowListener = { scope.mainHandler.post { scheduleNetFlows() } }
        FlowLog.subscribe(netFlowListener!!)
        netRulesListener = { scope.mainHandler.post { scheduleNetFlows() } }
        BlockRules.subscribe(netRulesListener!!)
        dnsListener = { scope.mainHandler.post { scheduleNetFlows() } }
        DnsEvents.subscribe(dnsListener!!)
    }

    private fun buildNetworkContent() {
        AdDomains.ensureLoaded(activity)
        netBraceMenu = BraceMenu(activity)
        netFlowList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * activity.resources.displayMetrics.density).toInt(), 0, 0)
        }
        networkInner.addView(netFlowList)
    }

    /** 顶部固定的实时看板：搜索行 + 左（速率/总量/计数） | 右（堆叠占比条 + 图例）。 */
    private fun buildDashboard(): LinearLayout {
        val density = activity.resources.displayMetrics.density
        val dim = scope.cOnSurfaceVariant

        netSearch = EditText(activity).apply {
            hint = activity.getString(R.string.net_search_hint)
            setHintTextColor(dim)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_COMPACT
            setSingleLine(true)
            setBackgroundColor(UiTokens.searchBg)
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) = scheduleNetFlows()
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        netStatsRate = statValue().apply { text = "${upArrow()} 0B/s  ${downArrow()} 0B/s" }
        netStatsTotal = statValue().apply { text = "${upArrow()} 0B  ${downArrow()} 0B" }
        netStatsCount = statValue().apply { text = "0" }

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(dashboardStatColumn(dim))
            addView(dashboardChartColumn(density))
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(scope.cSurface)
                setStroke((density).toInt(), 0x33FFFFFF)
                cornerRadius = 12 * density
            }
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            addView(netSearch)
            addView(body, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() })
        }
    }

    private fun statValue(): TextView {
        val out = TextView(activity)
        out.setTextColor(Color.WHITE)
        out.textSize = UiTokens.TEXT_COMPACT
        out.typeface = Typeface.MONOSPACE
        out.maxLines = 2
        return out
    }

    private fun statLabel(text: String, dim: Int): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(dim)
        textSize = 11f
    }

    private fun dashboardStatColumn(dim: Int): LinearLayout {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun pair(label: String, value: TextView) {
            col.addView(statLabel(label, dim))
            col.addView(value)
        }
        pair(activity.getString(R.string.net_dash_rate), netStatsRate)
        pair(activity.getString(R.string.net_dash_total), netStatsTotal)
        pair(activity.getString(R.string.net_dash_counters), netStatsCount)
        return col
    }

    private fun dashboardChartColumn(density: Float): LinearLayout {
        netStackBar = StackedBarView(activity)
        netLegend = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(netStackBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (14 * density).toInt()
            ).apply { topMargin = (4 * density).toInt() })
            addView(netLegend, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() })
        }
    }

    /** 实时看板渲染：速率/总量/计数走增量计数器，堆叠条与图例共用一次聚合。 */
    private fun renderDashboard(rows: List<FlowEntry>) {
        if (!::netStackBar.isInitialized || !::netStatsRate.isInitialized) return
        val c = FlowLog.counters()
        val now = System.currentTimeMillis()
        if (lastTickMs != 0L) {
            val dt = (now - lastTickMs) / 1000.0
            if (dt > 0.0) {
                val up = ((c.totalUp - lastTickUp) / dt).coerceAtLeast(0.0)
                val down = ((c.totalDown - lastTickDown) / dt).coerceAtLeast(0.0)
                netStatsRate.text = "${upArrow()} ${formatSpeed(up)}/s  ${downArrow()} ${formatSpeed(down)}/s"
            }
        }
        lastTickUp = c.totalUp
        lastTickDown = c.totalDown
        lastTickMs = now

        netStatsTotal.text = "${upArrow()} ${formatBytes(c.totalUp)}  ${downArrow()} ${formatBytes(c.totalDown)}"
        netStatsCount.text = "${activity.getString(R.string.net_dash_active)} ${c.active} · " +
            "${activity.getString(R.string.net_dash_blocked)} ${c.blocked} · " +
            "${activity.getString(R.string.net_dash_dns)} ${DnsEvents.count()}"

        val top = aggregateTop(rows)
        val totalBytes = top.sumOf { it.bytes }
        val slices = ArrayList<Pair<Long, Int>>()
        for (i in 0 until minOf(top.size, 3)) slices.add(top[i].bytes to chartPalette[i])
        var othersBytes = 0L
        if (top.size > 3) othersBytes = top.drop(3).sumOf { it.bytes }
        if (othersBytes > 0L) slices.add(othersBytes to chartPalette[3])
        netStackBar.setData(slices)
        buildNetLegend(top, othersBytes, totalBytes)
    }

    private data class TopBucket(val label: String, val bytes: Long)

    private fun aggregateTop(rows: List<FlowEntry>): List<TopBucket> {
        val map = HashMap<String, Long>()
        for (f in rows) {
            val label = f.domain ?: f.sni ?: f.dstIp
            map[label] = (map[label] ?: 0L) + f.bytesUp + f.bytesDown
        }
        val out = ArrayList<TopBucket>(map.size)
        for ((k, v) in map) out.add(TopBucket(k, v))
        out.sortByDescending { it.bytes }
        return out
    }

    private fun buildNetLegend(top: List<TopBucket>, othersBytes: Long, totalBytes: Long) {
        netLegend.removeAllViews()
        val density = activity.resources.displayMetrics.density
        if (top.isEmpty()) {
            netLegend.addView(TextView(activity).apply {
                text = activity.getString(R.string.net_dash_idle)
                setTextColor(scope.cOnSurfaceVariant)
                textSize = UiTokens.TEXT_COMPACT
            })
            return
        }
        for (i in 0 until minOf(top.size, 3)) {
            netLegend.addView(legendRow(chartPalette[i], top[i].label, top[i].bytes, totalBytes, density))
        }
        if (othersBytes > 0L) {
            netLegend.addView(
                legendRow(chartPalette[3], activity.getString(R.string.net_others), othersBytes, totalBytes, density)
            )
        }
    }

    private fun legendRow(color: Int, label: String, bytes: Long, total: Long, density: Float): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
            addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), (8 * density).toInt())
                setBackgroundColor(color)
            })
            addView(TextView(activity).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_META
                typeface = Typeface.MONOSPACE
                maxLines = 2
                setPadding((6 * density).toInt(), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(activity).apply {
                text = "${formatBytes(bytes)} ${pct(bytes, total)}%"
                setTextColor(scope.cOnSurfaceVariant)
                textSize = UiTokens.TEXT_META
                typeface = Typeface.MONOSPACE
                setPadding((6 * density).toInt(), 0, 0, 0)
            })
        }

    private fun pct(part: Long, total: Long): Int =
        if (total <= 0L) 0 else ((part * 100L + total - 1L) / total).toInt()

    private fun upArrow(): String = "↑"

    private fun downArrow(): String = "↓"

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
                        { count ->
                            refreshNetTab()
                            if (count > 1) status.showTempStatus(activity.getString(R.string.net_multi_app_warn))
                        }, scope.theme
                    ).show()
                }
            ))
            addView(gridRow(
                barButton(activity.getString(R.string.net_clear), scope.cOutline) {
                    FlowLog.clear()
                    DnsEvents.clear()
                    DnsMap.clear()
                    netExpandedIds.clear()
                    VpnFlowExporter.clearNow()
                    renderNetFlows()
                    status.showTempStatus(activity.getString(R.string.net_cleared))
                },
                barButton(activity.getString(R.string.net_view_log), scope.cOutline) { showNetLogDialog() }
            ))
            addView(gridRow(
                barButton(activity.getString(R.string.net_rank), scope.cOutline) { showNetRankDialog() },
                barButton(activity.getString(R.string.net_dns), scope.cOutline) { showNetDnsDialog() },
                barButton(activity.getString(R.string.net_adlist), scope.cOutline) { refreshAdDomains() }
            ))
        }
    }

    private fun showNetRankDialog() {
        val density = activity.resources.displayMetrics.density
        val rows = FlowLog.list()
        val listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun buckets(byPort: Boolean): List<Pair<String, Long>> {
            val map = HashMap<String, Long>()
            for (f in rows) {
                val key = if (byPort) "${f.proto}:${f.dstPort}" else (f.domain ?: f.sni ?: f.dstIp)
                map[key] = (map[key] ?: 0L) + f.bytesUp + f.bytesDown
            }
            return map.entries.sortedByDescending { it.value }
                .take(20).map { it.key to it.value }
        }
        fun refresh(byPort: Boolean) {
            listBox.removeAllViews()
            val buckets = buckets(byPort)
            if (buckets.isEmpty()) {
                listBox.addView(TextView(activity).apply {
                    text = activity.getString(R.string.net_rank_empty)
                    setTextColor(scope.cOnSurfaceVariant)
                    textSize = UiTokens.TEXT_COMPACT
                    setPadding(0, (8 * density).toInt(), 0, 0)
                })
                return
            }
            for ((index, bucket) in buckets.withIndex()) {
                listBox.addView(TextView(activity).apply {
                    text = "${index + 1}. ${bucket.first}  ${formatBytes(bucket.second)}"
                    setTextColor(Color.WHITE)
                    textSize = UiTokens.TEXT_COMPACT
                    typeface = Typeface.MONOSPACE
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, (6 * density).toInt(), 0, 0)
                })
            }
        }
        fun modeButton(text: String, onClick: () -> Unit): Button = Button(activity).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            isAllCaps = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            ButtonStyle.apply(this, scope.cOutline)
            setOnClickListener { onClick() }
        }
        val modeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 4, 4, 4)
            addView(modeButton(activity.getString(R.string.net_rank_domain)) { refresh(false) })
            addView(modeButton(activity.getString(R.string.net_rank_port)) { refresh(true) })
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(modeRow)
            addView(listBox)
        }
        refresh(false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.net_rank_title))
            .setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton(activity.getString(R.string.close), null)
            .create()
        DialogStyler.apply(dialog, scope.theme)
        dialog.show()
    }

    private fun showNetDnsDialog() {
        val density = activity.resources.displayMetrics.density
        val list = DnsEvents.list()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), 0)
        }
        if (list.isEmpty()) {
            content.addView(TextView(activity).apply {
                text = activity.getString(R.string.net_dns_empty)
                setTextColor(scope.cOnSurfaceVariant)
                textSize = UiTokens.TEXT_COMPACT
            })
        } else {
            for ((time, qname) in list) {
                content.addView(TextView(activity).apply {
                    text = "$time  $qname"
                    setTextColor(Color.WHITE)
                    textSize = UiTokens.TEXT_COMPACT
                    typeface = Typeface.MONOSPACE
                    setPadding(0, (6 * density).toInt(), 0, 0)
                })
            }
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.net_dns_title))
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(activity.getString(R.string.close), null)
            .create()
        DialogStyler.apply(dialog, scope.theme)
        dialog.show()
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
        val runningNow = NetVpnService.isRunning
        if (runningNow && activity.currentTab == 2 && !netTickerPosted) {
            netTickerPosted = true
            scope.mainHandler.postDelayed(netTicker, 1000L)
        } else if (!runningNow) {
            netTickerPosted = false
            scope.mainHandler.removeCallbacks(netTicker)
        }
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
        val rows = FlowLog.list()
        val sorted = rows.filter { it.state == "OPEN" || it.state == "UDP" } +
            rows.filter { it.state != "OPEN" && it.state != "UDP" }
        renderDashboard(sorted)
        val q = if (::netSearch.isInitialized) netSearch.text?.toString()?.trim().orEmpty().lowercase() else ""
        val filtered = if (q.isEmpty()) sorted else sorted.filter { it.matchesQuery(q) }
        netFlowList.removeAllViews()
        if (filtered.isEmpty()) {
            netFlowList.addView(TextView(activity).apply {
                text = activity.getString(R.string.net_empty)
                setTextColor(scope.cOnSurfaceVariant)
                textSize = UiTokens.TEXT_COMPACT
                setPadding(0, 8, 0, 8)
            })
            return
        }
        val density = activity.resources.displayMetrics.density
        netFlowList.addView(TextView(activity).apply {
            text = activity.getString(R.string.net_tag_note)
            setTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, (4 * density).toInt())
        })
        val limit = minOf(filtered.size, 80)
        for (i in 0 until limit) {
            val f = filtered[i]
            netFlowList.addView(buildFlowRow(f))
            netFlowList.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(scope.cOutline)
            })
        }
    }

    private fun FlowEntry.matchesQuery(q: String): Boolean =
        domain?.contains(q) == true || sni?.contains(q) == true || http?.contains(q) == true ||
            dstIp.contains(q) || proto.contains(q) || dstPort.toString().contains(q)

    private fun buildFlowRow(f: FlowEntry): View {
        val density = activity.resources.displayMetrics.density
        val ip = f.dstIp
        val domain = f.domain
        val blocked = f.state == "BLOCKED" || BlockRules.isBlocked(ip, domain)
        val main = if (!domain.isNullOrBlank()) domain else if (!f.sni.isNullOrBlank()) f.sni else ip
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
            } else {
                val tags = FlowClassifier.classify(
                    FlowClassifier.FlowSample(
                        f.bytesUp, f.bytesDown, f.startMs, f.durMs, System.currentTimeMillis(),
                        f.state, f.dstPort, AdDomains.adOf(domain) != null
                    )
                )
                for (t in tags.take(2)) {
                    addView(tagChip(t, density))
                }
            }
        })
        row.addView(TextView(activity).apply {
            text = buildFlowInfo(f)
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

    private fun tagChip(t: FlowClassifier.TagScore, density: Float): TextView {
        val isAd = t.tag == FlowClassifier.Tag.AD
        val label = when (t.tag) {
            FlowClassifier.Tag.INTERACT -> activity.getString(R.string.flow_tag_interact)
            FlowClassifier.Tag.MEDIA -> activity.getString(R.string.flow_tag_media)
            FlowClassifier.Tag.HEARTBEAT -> activity.getString(R.string.flow_tag_heartbeat)
            FlowClassifier.Tag.UPLOAD -> activity.getString(R.string.flow_tag_upload)
            FlowClassifier.Tag.AD -> activity.getString(R.string.flow_tag_ad)
        }
        return TextView(activity).apply {
            text = "$label ${t.confidence}%"
            setTextColor(if (isAd) Color.parseColor("#FF5252") else scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            typeface = Typeface.MONOSPACE
            setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
            setBackgroundColor(if (isAd) Color.BLACK else UiTokens.searchBg)
        }
    }

    private fun buildFlowInfo(f: FlowEntry): String {
        val stateCn = when (f.state) {
            "OPEN" -> activity.getString(R.string.flow_state_open)
            "UDP" -> activity.getString(R.string.flow_state_udp)
            "BLOCKED" -> activity.getString(R.string.flow_blocked)
            else -> activity.getString(R.string.flow_state_closed)
        }
        return "${f.proto}:${f.dstPort} · ↑${formatBytes(f.bytesUp)} ↓${formatBytes(f.bytesDown)} · $stateCn · ${f.time}"
    }

    private fun buildFlowDetail(f: FlowEntry, domain: String?): String {
        val sb = StringBuilder()
        if (!domain.isNullOrBlank()) {
            sb.append(activity.getString(R.string.flow_server_fmt, f.dstIp))
        } else {
            sb.append(activity.getString(R.string.flow_no_domain))
        }
        if (!f.sni.isNullOrBlank()) {
            sb.append(" · ").append(activity.getString(R.string.net_detail_sni, f.sni))
        }
        if (!f.http.isNullOrBlank()) {
            sb.append('\n').append(activity.getString(R.string.net_detail_http, f.http))
        }
        sb.append('\n').append(activity.getString(R.string.net_detail_dur, formatDurMs(f)))
        return sb.toString()
    }

    private fun formatDurMs(f: FlowEntry): String {
        val ms = f.durMs ?: (System.currentTimeMillis() - f.startMs).coerceAtLeast(0L)
        return if (ms >= 1000) String.format("%.1fs", ms / 1000.0) else "${ms}ms"
    }

    private fun formatSpeed(bytesPerS: Double): String {
        if (bytesPerS < 1024.0) return "${bytesPerS.toLong()}B"
        if (bytesPerS < 1024.0 * 1024.0) return String.format("%.1fKB", bytesPerS / 1024.0)
        return String.format("%.2fMB", bytesPerS / (1024.0 * 1024.0))
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
        if (scope.settingsManager.loadCaptureApps().size > 1) {
            status.showTempStatus(activity.getString(R.string.net_multi_app_warn))
        }
        requestVpnPrepare()
    }

    private fun refreshAdDomains() {
        AdDomains.ensureLoaded(activity)
        status.showTempStatus(activity.getString(R.string.net_adlist_updating))
        AdDomains.refresh(activity) { res ->
            scope.mainHandler.post {
                val msg = when (res) {
                    is AdDomains.Result.Success -> activity.getString(R.string.net_adlist_ok_fmt, res.count)
                    AdDomains.Result.Failure -> activity.getString(R.string.net_adlist_fail)
                }
                status.showTempStatus(msg)
            }
        }
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
        netTickerPosted = false
        scope.mainHandler.removeCallbacks(netFlowRunnable)
        scope.mainHandler.removeCallbacks(netTicker)
        netFlowListener?.let { FlowLog.unsubscribe(it) }
        dnsListener?.let { DnsEvents.unsubscribe(it) }
        netRulesListener?.let { BlockRules.unsubscribe(it) }
        netFlowListener = null
        netRulesListener = null
        dnsListener = null
    }
}
