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
    private lateinit var lanStatusText: TextView
    private lateinit var lanUrlText: TextView

    /** LAN 小节（标题+状态+网址+双键），由 SettingsUiController 在上游代理之后插入。 */
    fun buildSettingsBlock(parent: LinearLayout) {
        parent.addView(TextView(activity).apply {
            text = activity.getString(R.string.lan_title)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_BODY
            setPadding(0, 0, 0, 4)
        })
        lanStatusText = TextView(activity).apply {
            text = activity.getString(R.string.lan_status_off)
            setTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 4)
        }
        parent.addView(lanStatusText)
        lanUrlText = TextView(activity).apply {
            text = activity.getString(R.string.lan_url_preview)
            setTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 12)
            setOnClickListener { copyLanUrl() }
        }
        parent.addView(lanUrlText)
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * activity.resources.displayMetrics.density).toInt(), 0, 0)
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
                ButtonStyle.apply(this, scope.cPrimary)
                setOnClickListener {
                    if (LanShareService.isRunning) stopLan() else startLan()
                }
            }
            lanAuthBtn = Button(activity).apply {
                text = activity.getString(R.string.lan_auth_btn)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 4
                }
                ButtonStyle.apply(this, scope.cOutline)
                setOnClickListener { showLanAuthDialog() }
            }
            addView(lanToggleBtn)
            addView(lanAuthBtn)
        })
        refreshLanRow()
    }

    fun refreshLanRow() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!::lanToggleBtn.isInitialized) return
        val running = LanShareService.isRunning
        val user = scope.settingsManager.lanUser()
        lanStatusText.text = if (running) {
            if (user.isEmpty()) activity.getString(R.string.lan_status_open) else activity.getString(R.string.lan_status_auth_fmt, user)
        } else {
            if (user.isEmpty()) activity.getString(R.string.lan_status_off) else activity.getString(R.string.lan_status_off_auth_fmt, user)
        }
        lanUrlText.text = if (running && LanShareService.lanUrl.isNotEmpty()) {
            activity.getString(R.string.lan_url_copy_fmt, LanShareService.lanUrl)
        } else {
            val ip = NetworkUtils.getLanIp(activity) ?: "…"
            activity.getString(R.string.lan_url_preview_ip_fmt, ip)
        }
        lanToggleBtn.text = if (running) activity.getString(R.string.lan_stop) else activity.getString(R.string.lan_start)
        ButtonStyle.apply(lanToggleBtn, if (running) scope.cError else scope.cPrimary)
        lanAuthBtn.isEnabled = !running
        lanAuthBtn.alpha = if (running) 0.5f else 1f
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
            setTextColor(Color.WHITE)
            setHintTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_BODY
            setSingleLine(true)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            setBackgroundColor(UiTokens.searchBg)
        }
        val passEdit = EditText(activity).apply {
            setText(scope.settingsManager.lanPass())
            hint = activity.getString(R.string.hint_password)
            setTextColor(Color.WHITE)
            setHintTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_BODY
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            setBackgroundColor(UiTokens.searchBg)
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
