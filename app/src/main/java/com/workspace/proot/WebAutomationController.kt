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
 * 无头浏览器设置小节（5.9.0）：照 [LanController] 的形态——**手动开关**、状态行、
 * 按钮按状态变色、清除缓存要二次确认，外加"一键复制连接信息"（端口 + 令牌 + 首条命令）。
 *
 * 服务本体在 [WebAutomationService]，产物在 [WebArtifacts]，闪烁提示规则在 [WebNoticeArbiter]。
 */
class WebAutomationController(
    private val activity: MainActivity,
    private val scope: AppScope,
    private val status: StatusController
) {
    private lateinit var toggleBtn: Button
    private lateinit var clearBtn: Button
    private var btnRow: LinearLayout? = null
    private lateinit var statusText: TextView
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
        parent.addView(TextView(activity).apply {
            text = activity.getString(R.string.web_settings_desc)
            setTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 4)
        })
        statusText = TextView(activity).apply {
            text = activity.getString(R.string.web_status_off)
            setTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 4)
        }
        parent.addView(statusText)
        copyText = TextView(activity).apply {
            text = activity.getString(R.string.web_copy_hint)
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
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 4
                }
                setOnClickListener { if (WebAutomationService.isRunning) stop() else start() }
            }
            clearBtn = Button(activity).apply {
                text = activity.getString(R.string.web_btn_clear)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 4
                }
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
        statusText.text = if (running) {
            activity.getString(R.string.web_status_on_fmt, WebProtocol.DEFAULT_PORT)
        } else {
            activity.getString(R.string.web_status_off)
        }
        toggleBtn.text = activity.getString(
            if (running) R.string.web_btn_stop else R.string.web_btn_start
        )
        applySegments()
        activity.refreshStatusBar()
    }

    private fun start() {
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
            appendLine("# TermLou 无头浏览器 · headless browser")
            appendLine("PORT=$port")
            appendLine("TOKEN=$token")
            appendLine("source ~/web/web.env")
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
