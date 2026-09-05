package com.workspace.proot

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 设置域：整页设置内容构建（字号/命令/磁贴/快捷/工坊/上游/LAN/保活/存储/语言）。
 * 原 MainActivity buildSettingsContent（~545 行）收归此处。
 */
class SettingsUiController(
    private val activity: MainActivity,
    private val scope: AppScope,
    private val status: StatusController,
    private val terminal: TerminalController,
    private val lan: LanController,
    private val overlay: OverlayCommandsController,
    private val lifecycle: LifecycleCoroutineScope,
    private val requestNotifPerm: () -> Unit
) {
    private var suppressSwitch = false
    private var batteryOptPending = false
    private var keepAliveSwitch: SwitchMaterial? = null

    fun buildInto(settingsWrapper: LinearLayout) {
        val density = activity.resources.displayMetrics.density
        val settingsScroll = TabSwipeScrollView(
            activity,
            onSwipeRight = { if (activity.currentTab == 3 && !activity.isSetupVisible()) activity.showTab(2) }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply {
                weight = 1f
            }
        }
        val settingsInner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        settingsScroll.addView(settingsInner)

        buildFontSection(settingsInner, density)
        settingsInner.addView(divider())
        buildShellSection(settingsInner)
        settingsInner.addView(divider())
        buildTileSection(settingsInner)
        settingsInner.addView(divider())
        buildQuickSection(settingsInner)
        settingsInner.addView(divider())
        buildWorkshopRow(
            settingsInner,
            activity.getString(R.string.settings_dialog_ws_title),
            activity.getString(R.string.settings_dialog_ws_desc)
        ) { overlay.openDialogMaker() }
        settingsInner.addView(divider())
        buildWorkshopRow(
            settingsInner,
            activity.getString(R.string.settings_splash_title),
            activity.getString(R.string.settings_splash_desc)
        ) { overlay.openSplashMaker() }
        settingsInner.addView(divider())
        buildUpstreamSection(settingsInner, density)
        settingsInner.addView(divider())
        lan.buildSettingsBlock(settingsInner)
        buildKeepAliveRow(settingsInner)
        buildStorageRow(settingsInner)
        settingsInner.addView(divider())
        buildLanguageRow(settingsInner)

        settingsWrapper.addView(settingsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val settingsFooter = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 0, 32, 0)
        }
        settingsFooter.addView(TextView(activity).apply {
            text = "TermLou v${try { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName } catch (_: Exception) { "1.3.1" }}"
            setTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 4)
        })
        settingsFooter.addView(TextView(activity).apply {
            val ss = android.text.SpannableString("Made by Lou with ♥")
            ss.setSpan(android.text.style.ForegroundColorSpan(Color.RED), ss.indexOf("♥"), ss.length, 0)
            text = ss
            setTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        })
        settingsWrapper.addView(settingsFooter)
    }

    private fun divider(): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            setMargins(0, 24, 0, 24)
        }
        setBackgroundColor(scope.cOutline)
    }

    private fun sectionTitle(parent: LinearLayout, text: String, body: Boolean = false) {
        parent.addView(TextView(activity).apply {
            this.text = text
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = if (body) UiTokens.TEXT_BODY else UiTokens.TEXT_TITLE
            setPadding(0, 0, 0, 4)
        })
    }

    private fun sectionDesc(parent: LinearLayout, text: String, bottom: Int = 12) {
        parent.addView(TextView(activity).apply {
            this.text = text
            setTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, bottom)
        })
    }

    private fun buildFontSection(parent: LinearLayout, density: Float) {
        sectionTitle(parent, activity.getString(R.string.settings_font_title))
        val slider = TickSlider(ContextThemeWrapper(activity, R.style.Theme_TermLou_Slider)).apply {
            valueFrom = 0f
            valueTo = 4f
            stepSize = 0f
            value = terminal.fontSizeIndex.toFloat()
            thumbTintList = ColorStateList.valueOf(scope.cPrimary)
            trackActiveTintList = ColorStateList.valueOf(scope.cPrimary)
            trackInactiveTintList = ColorStateList.valueOf(scope.cOutline)
            haloTintList = ColorStateList.valueOf((scope.cPrimary and 0x00FFFFFF) or (0x33 shl 24))
            setTickColors(scope.cPrimary, (scope.cOutline and 0x00FFFFFF) or (0x66 shl 24))
        }
        parent.addView(slider)
        terminal.bindFontSlider(slider)
    }

    private fun buildShellSection(parent: LinearLayout) {
        sectionTitle(parent, activity.getString(R.string.settings_shell_title))
        sectionDesc(parent, activity.getString(R.string.settings_shell_desc))
        val shellEdit = EditText(activity).apply {
            setText(scope.settingsManager.shellCmd)
            setTextColor(Color.WHITE)
            setBackgroundColor(scope.cOutline)
            textSize = UiTokens.TEXT_BODY
            setPadding(12, 8, 12, 8)
            setHint(activity.getString(R.string.hint_shell_cmd))
            setHintTextColor(scope.cOnSurfaceVariant)
        }
        parent.addView(shellEdit)
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
            addView(Button(activity).apply {
                text = activity.getString(R.string.save)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 4
                }
                ButtonStyle.apply(this, scope.cPrimary)
                setOnClickListener {
                    val cmd = shellEdit.text.toString().trim()
                    scope.settingsManager.setShellCmd(cmd)
                    status.showTempStatus(activity.getString(R.string.shell_saved))
                    activity.hideIme()
                }
            })
            addView(Button(activity).apply {
                text = activity.getString(R.string.reset)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 4
                }
                ButtonStyle.apply(this, scope.cOutline)
                setOnClickListener {
                    shellEdit.setText("")
                    scope.settingsManager.setShellCmd("")
                    status.showTempStatus(activity.getString(R.string.shell_inited_idle))
                    activity.hideIme()
                }
            })
        })
    }

    private fun buildTileSection(parent: LinearLayout) {
        sectionTitle(parent, activity.getString(R.string.settings_tile_title))
        sectionDesc(parent, activity.getString(R.string.settings_tile_desc))
        val tileEdit = EditText(activity).apply {
            setText(scope.settingsManager.tileCommand)
            setTextColor(Color.WHITE)
            setBackgroundColor(scope.cOutline)
            textSize = UiTokens.TEXT_BODY
            setPadding(12, 8, 12, 8)
            setHint(activity.getString(R.string.hint_tile_cmd))
            setHintTextColor(scope.cOnSurfaceVariant)
        }
        parent.addView(tileEdit)
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
            addView(Button(activity).apply {
                text = activity.getString(R.string.save)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 4
                }
                ButtonStyle.apply(this, scope.cPrimary)
                setOnClickListener {
                    val cmd = tileEdit.text.toString().trim()
                    scope.settingsManager.setTileCommand(cmd)
                    status.showTempStatus(activity.getString(R.string.tile_cmd_saved))
                    activity.hideIme()
                }
            })
            addView(Button(activity).apply {
                text = activity.getString(R.string.reset)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 4
                }
                ButtonStyle.apply(this, scope.cOutline)
                setOnClickListener {
                    tileEdit.setText(scope.settingsManager.shellCmd)
                    scope.settingsManager.setTileCommand(scope.settingsManager.shellCmd)
                    status.showTempStatus(activity.getString(R.string.inited))
                    activity.hideIme()
                }
            })
        })
    }

    private fun buildQuickSection(parent: LinearLayout) {
        sectionTitle(parent, activity.getString(R.string.settings_quick_title))
        sectionDesc(parent, activity.getString(R.string.settings_quick_desc))
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            addView(Button(activity).apply {
                text = activity.getString(R.string.pick_app)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 4
                }
                ButtonStyle.apply(this, scope.cPrimary)
                setOnClickListener { this@SettingsUiController.overlay.showAppPicker() }
            })
            addView(Button(activity).apply {
                text = activity.getString(R.string.reset)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 4
                }
                ButtonStyle.apply(this, scope.cOutline)
                setOnClickListener { this@SettingsUiController.overlay.onQuickInitClick() }
            })
        })
    }

    private fun buildWorkshopRow(parent: LinearLayout, title: String, desc: String, onOpen: () -> Unit) {
        sectionTitle(parent, title)
        sectionDesc(parent, desc)
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(activity).apply {
                text = activity.getString(R.string.open_workshop)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                ButtonStyle.apply(this, scope.cPrimary)
                setOnClickListener { onOpen() }
            })
        })
    }

    private fun buildUpstreamSection(parent: LinearLayout, density: Float) {
        sectionTitle(parent, activity.getString(R.string.settings_advanced))
        sectionTitle(parent, activity.getString(R.string.upstream_title), body = true)
        sectionDesc(parent, activity.getString(R.string.upstream_desc))
        val upEdit = EditText(activity).apply {
            setText(scope.settingsManager.netUpstream())
            setTextColor(Color.WHITE)
            setHintTextColor(scope.cOnSurfaceVariant)
            setHint("socks5://127.0.0.1:1080")
            textSize = UiTokens.TEXT_BODY
            setSingleLine(true)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            setBackgroundColor(UiTokens.searchBg)
        }
        parent.addView(upEdit)
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * density).toInt(), 0, 0)
            addView(Button(activity).apply {
                text = activity.getString(R.string.save)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                isAllCaps = true
                setPadding(16, 6, 16, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 4
                }
                ButtonStyle.apply(this, scope.cPrimary)
                setOnClickListener {
                    scope.settingsManager.setNetUpstream(upEdit.text.toString().trim())
                    status.showTempStatus(activity.getString(R.string.upstream_saved))
                    activity.hideIme()
                }
            })
            addView(Button(activity).apply {
                text = activity.getString(R.string.clear)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                isAllCaps = true
                setPadding(16, 6, 16, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 4
                }
                ButtonStyle.apply(this, scope.cOutline)
                setOnClickListener {
                    upEdit.setText("")
                    scope.settingsManager.setNetUpstream("")
                    status.showTempStatus(activity.getString(R.string.upstream_cleared))
                    activity.hideIme()
                }
            })
        })
    }

    private fun buildKeepAliveRow(parent: LinearLayout) {
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.keepalive_title)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                textSize = UiTokens.TEXT_BODY
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val keepAliveSw = SwitchMaterial(ContextThemeWrapper(activity, R.style.Theme_TermLou_Slider)).apply {
                elevation = 8f
                thumbTintList = controlTint()
                trackTintList = switchTrackTint()
                isChecked = scope.settingsManager.keepAlive
                setOnCheckedChangeListener { _, isChecked ->
                    if (suppressSwitch) return@setOnCheckedChangeListener
                    if (isChecked) {
                        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            activity.startForegroundService(Intent(activity, TermKeepAliveService::class.java))
                            scope.settingsManager.setKeepAlive(true)
                            status.showTempStatus(activity.getString(R.string.keepalive_on))
                            ensureIgnoreBatteryOptimizations()
                        } else {
                            requestNotifPerm()
                        }
                    } else {
                        activity.stopService(Intent(activity, TermKeepAliveService::class.java))
                        scope.settingsManager.setKeepAlive(false)
                        status.showTempStatus(activity.getString(R.string.keepalive_off))
                    }
                }
            }
            keepAliveSwitch = keepAliveSw
            addView(keepAliveSw)
        })
    }

    fun onNotifPermResult(granted: Boolean) {
        suppressSwitch = true
        scope.settingsManager.setKeepAlive(false)
        keepAliveSwitch?.isChecked = false
        suppressSwitch = false
        if (granted) {
            activity.startForegroundService(Intent(activity, TermKeepAliveService::class.java))
            scope.settingsManager.setKeepAlive(true)
            status.showTempStatus(activity.getString(R.string.keepalive_on))
            ensureIgnoreBatteryOptimizations()
        } else {
            status.showTempStatus(activity.getString(R.string.notif_perm_needed))
        }
    }

    private fun buildStorageRow(parent: LinearLayout) {
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = activity.getString(R.string.storage_title)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                textSize = UiTokens.TEXT_TITLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(activity).apply {
                text = activity.getString(R.string.view)
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setPadding(16, 6, 16, 6)
                ButtonStyle.apply(this, scope.cOutline)
                setOnClickListener { showStorageDialog() }
            })
        })
    }

    private fun buildLanguageRow(parent: LinearLayout) {
        val langZh = AppLang.isChinese(activity)
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.lang_title) + " " + if (langZh) "🇨🇳" else "🇺🇸"
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                textSize = UiTokens.TEXT_TITLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(SwitchMaterial(ContextThemeWrapper(activity, R.style.Theme_TermLou_Slider)).apply {
                elevation = 8f
                thumbTintList = controlTint()
                trackTintList = switchTrackTint()
                isChecked = langZh
                setOnCheckedChangeListener { _, isChecked ->
                    if (suppressSwitch) return@setOnCheckedChangeListener
                    // 同步落盘后整进程重启：冷启动路径（Application.onCreate → AppLang.apply 读盘）
                    // 是唯一无竞态的生效方式；热重建依赖的多方重建在分身副用户下会错位（见 4.4.1）。
                    scope.settingsManager.setLangExplicit(if (isChecked) AppLang.LANG_ZH else AppLang.LANG_EN)
                    AppLang.apply(activity)
                    restartApp()
                }
            })
        })
        parent.addView(TextView(activity).apply {
            text = activity.getString(if (langZh) R.string.lang_sub_zh else R.string.lang_sub_en)
            setTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_META
            setPadding(0, 0, 0, 0)
        })
    }

    /**
     * 整进程重启：先递 launcher intent 再自杀，系统会拉起新进程。
     * 调用前必须保证 pref 已同步落盘（setLangExplicit 用 commit）。
     */
    private fun restartApp() {
        runCatching {
            val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (launch != null) activity.startActivity(launch)
        }
        kotlin.system.exitProcess(0)
    }

    private fun controlTint(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        ),
        intArrayOf(scope.cPrimary, scope.cOutline)
    )

    private fun switchTrackTint(): ColorStateList {
        val checked = (scope.cPrimary and 0x00FFFFFF) or (0x66 shl 24)
        val unchecked = (scope.cOutline and 0x00FFFFFF) or (0x66 shl 24)
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(checked, unchecked)
        )
    }

    private fun ensureIgnoreBatteryOptimizations() {
        val pm = activity.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(activity.packageName)) return
        try {
            batteryOptPending = true
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
            )
        } catch (e: Exception) {
            try {
                batteryOptPending = true
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                status.showTempStatus(activity.getString(R.string.battery_manual))
            }
        }
    }

    fun onResume() {
        if (!batteryOptPending) return
        batteryOptPending = false
        val pm = activity.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(activity.packageName)) {
            status.showTempStatus(activity.getString(R.string.battery_exempted))
        }
    }

    private fun showStorageDialog() {
        StorageDialog(activity, scope.theme, scope.wsFiles, lifecycle).show()
    }
}
