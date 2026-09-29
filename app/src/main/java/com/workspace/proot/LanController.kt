package com.workspace.proot

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * LAN 域：设置区 LAN 小节 UI + 服务启停/认证/复制链接。
 * 原 MainActivity LAN 相关 ~150 行收归此处。
 */
class LanController(
    private val activity: MainActivity,
    private val scope: AppScope,
    private val status: StatusController
) {
    private lateinit var lanToggleBtn: Button
    private lateinit var lanAuthBtn: Button
    private var lanBtnRow: LinearLayout? = null
    private lateinit var lanUrlText: TextView

    /** LAN 小节（标题 + 复制行 + 双键），由 SettingsUiController 在上游代理之后插入。 */
    fun buildSettingsBlock(parent: LinearLayout) {
        parent.addView(TextView(activity).apply {
            text = activity.getString(R.string.lan_title)
            setTextColor(scope.cOnSurface)
            typeface = Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_BODY
            setPadding(0, 0, 0, 4)
        })
        // 5.9.4：删掉原来那行状态文本。开关状态由按钮的填充色表达；
        // "需认证"改由认证按钮自己的文字承载（见 refreshLanRow）。
        lanUrlText = TextView(activity).apply {
            text = activity.getString(R.string.lan_url_preview)
            setTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 12)
            setOnClickListener { copyLanUrl() }
        }
        parent.addView(lanUrlText)
        val density = activity.resources.displayMetrics.density
        val lanBtnRow = buildLanButtons()
        this.lanBtnRow = lanBtnRow
        parent.addView(lanBtnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (8 * density).toInt()
        })
        applyLanSegments()
        refreshLanRow()
    }

    private fun buildLanButtons(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            lanToggleBtn = Button(activity).apply {
                text = activity.getString(R.string.lan_start)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 4
                }
                setOnClickListener {
                    if (LanShareService.isRunning) stopLan() else startLan()
                }
            }
            lanAuthBtn = Button(activity).apply {
                applyFittingLabel(
                    activity.getString(R.string.lan_auth_btn),
                    activity.getString(R.string.lan_auth_btn_short)
                )
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 4
                }
                setOnClickListener { showLanAuthDialog() }
            }
            addView(lanToggleBtn)
            addView(lanAuthBtn)
        }
    }

    private fun applyLanSegments() {
        val row = lanBtnRow ?: return
        val running = LanShareService.isRunning
        val fill = if (running) {
            SegmentStyle.Fill(scope.cError, 0xFFFFFFFF.toInt())
        } else {
            null
        }
        SegmentStyle.applyRow(row, listOf(fill, null), scope.cOutline, scope.cOnSurface)
    }

    fun refreshLanRow() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!::lanToggleBtn.isInitialized) return
        val running = LanShareService.isRunning
        val user = scope.settingsManager.lanUser()
        lanUrlText.text = if (running && LanShareService.lanUrl.isNotEmpty()) {
            activity.getString(R.string.lan_url_copy_fmt, LanShareService.lanUrl)
        } else {
            val ip = NetworkUtils.getLanIp(activity) ?: "…"
            activity.getString(R.string.lan_url_preview_ip_fmt, ip)
        }
        lanToggleBtn.text = if (running) activity.getString(R.string.lan_stop) else activity.getString(R.string.lan_start)
        // 认证状态长在认证按钮上：存了认证信息就写"需认证"，一眼能看出这台机子要口令。
        // （此前只有那行状态文本说这事，删掉状态行后这里是唯一出处。）
        lanAuthBtn.applyFittingLabel(
            activity.getString(if (user.isEmpty()) R.string.lan_auth_btn else R.string.lan_auth_btn_set),
            activity.getString(R.string.lan_auth_btn_short)
        )
        lanAuthBtn.isEnabled = !running
        lanAuthBtn.alpha = if (running) 0.5f else 1f
        applyLanSegments()
    }

    private fun startLan() {
        LanShareService.start(activity)
        status.showTempStatus(activity.getString(R.string.lan_starting))
        scope.mainHandler.postDelayed({ refreshLanRow() }, 1500)
        scope.mainHandler.postDelayed({ refreshLanRow() }, 4000)
    }

    private fun stopLan() {
        LanShareService.stop(activity)
        status.showTempStatus(activity.getString(R.string.lan_stopped))
        scope.mainHandler.postDelayed({ refreshLanRow() }, 1200)
    }

    private fun copyLanUrl() {
        val url = if (LanShareService.isRunning && LanShareService.lanUrl.isNotEmpty()) {
            LanShareService.lanUrl
        } else {
            val ip = NetworkUtils.getLanIp(activity) ?: return
            "http://$ip:8080"
        }
        runCatching {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("lan", url))
        }
        status.showTempStatus(activity.getString(R.string.copied_fmt, url))
    }

    private fun showLanAuthDialog() {
        if (LanShareService.isRunning) {
            status.showTempStatus(activity.getString(R.string.lan_stop_first))
            return
        }
        val density = activity.resources.displayMetrics.density
        val userEdit = EditText(activity).apply {
            setText(scope.settingsManager.lanUser())
            hint = activity.getString(R.string.hint_lan_user)
            setSingleLine(true)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
        }.also {
            FieldStyle.applyOutlined(it, scope.theme, UiTokens.TEXT_BODY)
        }
        val passEdit = EditText(activity).apply {
            setText(scope.settingsManager.lanPass())
            hint = activity.getString(R.string.hint_password)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
        }.also {
            FieldStyle.applyOutlined(it, scope.theme, UiTokens.TEXT_BODY)
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            addView(userEdit)
            addView(passEdit, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() })
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.lan_auth_title))
            .setView(body)
            .setPositiveButton(activity.getString(R.string.save)) { _, _ ->
                scope.settingsManager.setLanAuth(
                    userEdit.text.toString().trim(),
                    passEdit.text.toString()
                )
                activity.hideIme()
                refreshLanRow()
                status.showTempStatus(activity.getString(R.string.lan_auth_saved))
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .create()
        dialog.show()
        DialogStyler.apply(dialog, scope.theme)
    }
}
