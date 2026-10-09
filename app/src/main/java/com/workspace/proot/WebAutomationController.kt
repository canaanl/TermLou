package com.workspace.proot

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 浏览器设置小节（5.9.0）：照 [LanController] 的形态——**手动开关**、状态行、
 * 按钮按状态变色、清除缓存要二次确认，外加"一键复制连接信息"（端口 + 令牌 + 首条命令）。
 *
 * 服务本体在 [WebAutomationService]，产物在 [WebArtifacts]，闪烁提示规则在 [WebNoticeArbiter]。
 *
 * 5.9.27：浏览器改成**可见悬浮窗**，`SYSTEM_ALERT_WINDOW` 从"落回兜底才要"
 * 变成**硬需求** —— 开启前就检查，没权限先跳系统授权页（同 [LauncherTileService] 的做法）。
 */
class WebAutomationController(
    private val activity: MainActivity,
    private val scope: AppScope,
    private val status: StatusController
) {
    private lateinit var toggleBtn: Button
    private lateinit var clearBtn: Button
    private var btnRow: LinearLayout? = null
    private lateinit var copyText: TextView

    /** 由 SettingsUiController 插在 LAN 小节之后（同属"高级"区）。 */
    fun buildSettingsBlock(parent: LinearLayout) {
        val density = activity.resources.displayMetrics.density
        parent.addView(TextView(activity).apply {
            text = activity.getString(R.string.web_settings_title)
            setTextColor(scope.cOnSurface)
            typeface = Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_BODY
            setPadding(0, 0, 0, 4)
        })
        // 5.9.4：这里原本有一整段功能说明（"给 Linux 里的 AI agent 用的无头浏览器…"），
        // 与终端 Tab 的说明页重复。
        // 5.9.4：又把状态行去掉了 —— 开关状态由按钮填充色表达，端口在下面这行里，
        // 于是这一块和 LAN 行长得一样：标题 + 点击复制行 + 按钮。
        copyText = TextView(activity).apply {
            text = activity.getString(R.string.web_copy_hint, WebProtocol.DEFAULT_PORT)
            setTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 12)
            setOnClickListener { copyConnectionInfo() }
        }
        parent.addView(copyText)
        val row = buildButtons()
        btnRow = row
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * density).toInt() })
        refreshRow()
    }

    private fun buildButtons(): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            toggleBtn = Button(activity).apply {
                text = activity.getString(R.string.web_btn_start)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                // 5.9.4 修：这里原本给 marginEnd/marginStart = 4dp，靠 SegmentStyle.applyRow
                // 里的 relayoutMargins() 在 applyRow 时清掉。但本行会**反复** applyRow
                // （refreshRow 在开关服务后 1.2s / 3s 又调一次），于是"已布局 → 下一帧才清
                // 间隙"之间存在一帧错位：填充色和分割线对不齐，露出底色小缝（用户实机偶发）。
                // 直接建 0 间隙，从第一帧起就是紧贴的。
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { if (WebAutomationService.isRunning) stop() else start() }
            }
            clearBtn = Button(activity).apply {
                text = activity.getString(R.string.web_btn_clear)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { confirmClear() }
            }
            addView(toggleBtn)
            addView(clearBtn)
        }

    /** 服务开着时按钮变红（危险动作 = 停止），与 LAN 行同一套视觉语言。 */
    private fun applySegments() {
        val row = btnRow ?: return
        val fill = if (WebAutomationService.isRunning) {
            SegmentStyle.Fill(scope.cError, 0xFFFFFFFF.toInt())
        } else {
            null
        }
        SegmentStyle.applyRow(row, listOf(fill, null), scope.cOutline, scope.cOnSurface)
    }

    fun refreshRow() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!::toggleBtn.isInitialized) return
        val running = WebAutomationService.isRunning
        toggleBtn.text = activity.getString(
            if (running) R.string.web_btn_stop else R.string.web_btn_start
        )
        // 5.9.6：服务在跑时**不给删**。置灰而不是"点了报错"，和网络上那个
        // 抓包按钮没选应用时一个样（见 NetworkController.refreshNetTab）。
        // 顺带把"agent 往 web/ 里写的东西会被清掉"这个顾虑一并消掉：
        // 服务运行中这个按钮按不到，只有停止后的残留物会被清。
        clearBtn.isEnabled = !running
        clearBtn.alpha = if (running) 0.5f else 1f
        applySegments()
        activity.refreshStatusBar()
    }

    private fun start() {
        // 5.9.27：**悬浮窗权限是硬需求**。以前无头为主，权限只在"自检落回"那条路上要；
        // 现在浏览器就要显示在小窗里，所以开服务之前先问一句 ——
        // 不问的话用户开完服务才发现屏幕上什么都没有。
        if (!android.provider.Settings.canDrawOverlays(activity)) {
            status.showTempStatus(activity.getString(R.string.web_overlay_fallback))
            runCatching {
                activity.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${activity.packageName}")
                    )
                )
            }
            return
        }
        WebAutomationService.start(activity)
        status.showTempStatus(activity.getString(R.string.web_starting_toast))
        scope.mainHandler.postDelayed({ refreshRow() }, 1200)
        scope.mainHandler.postDelayed({ refreshRow() }, 3000)
    }

    private fun stop() {
        WebAutomationService.stop(activity)
        status.showTempStatus(activity.getString(R.string.web_stopped_toast))
        scope.mainHandler.postDelayed({ refreshRow() }, 1200)
    }

    /** 一键复制：端口 + 令牌 + 能直接粘进 agent 的首条命令。 */
    private fun copyConnectionInfo() {
        val token = WebAutomationService.currentToken(activity)
        val port = WebProtocol.DEFAULT_PORT
        val text = buildString {
            appendLine("# TermLou 浏览器 · browser")
            appendLine("PORT=$port")
            appendLine("TOKEN=$token")
            appendLine("source ${WebProtocol.WEB_DIR}/web.env")
            append("curl -s -H \"X-Token: $token\" -d '{\"op\":\"ping\"}' http://127.0.0.1:$port/op")
        }
        runCatching {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("termlou-web", text))
        }
        status.showTempStatus(activity.getString(R.string.web_copied))
    }

    /** 清除缓存要二次确认：删的是截图与 cookie 文件，误删就没了。 */
    private fun confirmClear() {
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.web_clear_confirm))
            .setPositiveButton(activity.getString(R.string.clear)) { _, _ ->
                // 置灰只挡入口，挡不住这个时序：确认框开着的时候服务被启动，
                // 再点确定就会去删一个正在用的目录。这里补一刀。
                if (WebAutomationService.isRunning) {
                    status.showTempStatus(activity.getString(R.string.web_clear_busy))
                    return@setPositiveButton
                }
                val removed = WebArtifacts.clearArtifacts(activity)
                status.showTempStatus(
                    if (removed > 0) {
                        activity.getString(R.string.web_cleared_fmt, removed)
                    } else {
                        activity.getString(R.string.web_cleared_none)
                    }
                )
                refreshRow()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .showStyled(scope.theme)
    }
}
