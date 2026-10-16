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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/**
 * 设置域：整页设置内容构建（字号/命令/磁贴/快捷/工坊/上游/LAN/保活/语言）。
 * 系统信息已搬到终端 Tab 介绍页顶部，这里不再设入口。
 * 原 MainActivity buildSettingsContent（~545 行）收归此处。
 */
class SettingsUiController(
    private val activity: MainActivity,
    private val scope: AppScope,
    private val status: StatusController,
    private val terminal: TerminalController,
    private val lan: LanController,
    private val web: WebAutomationController,
    private val overlay: OverlayCommandsController,
    private val requestNotifPerm: () -> Unit
) {
    private var suppressSwitch = false
    private var batteryOptPending = false
    private var keepAliveSwitch: MaterialSwitch? = null

    fun buildInto(settingsWrapper: LinearLayout) {
        val density = activity.resources.displayMetrics.density
        // 5.9.53：同上，页签切换交给 ViewPager2，横向回调删掉。
        val settingsScroll = TabSwipeScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply {
                weight = 1f
            }
        }
        val settingsInner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // 5.9.40：原 32dp。MD3 明确规定屏幕左右边距 16dp（32dp 是手机上的两倍，
            // 内容被压窄、可读宽度白白少一半）。这里回到 MD3 的规定值。
            setPaddingMd(UiTokens.SPACE_4, UiTokens.SPACE_4, UiTokens.SPACE_4, UiTokens.SPACE_4)
        }
        settingsScroll.addView(settingsInner)

        buildDisplaySection(settingsInner, density)
        settingsInner.addView(divider())
        buildNotesSection(settingsInner)
        settingsInner.addView(divider())
        sectionTitle(settingsInner, activity.getString(R.string.settings_commands_title))
        buildShellSection(settingsInner)
        buildTileSection(settingsInner)
        settingsInner.addView(divider())
        buildQuickSection(settingsInner)
        settingsInner.addView(divider())
        sectionTitle(settingsInner, activity.getString(R.string.settings_workshop_title))
        buildWorkshopRow(
            settingsInner,
            activity.getString(R.string.settings_dialog_ws_title)
        ) { overlay.openDialogMaker() }
        buildWorkshopRow(
            settingsInner,
            activity.getString(R.string.settings_splash_title)
        ) { overlay.openSplashMaker() }
        settingsInner.addView(divider())
        buildUpstreamSection(settingsInner, density)
        lan.buildSettingsBlock(settingsInner)
        web.buildSettingsBlock(settingsInner)
        buildKeepAliveRow(settingsInner)

        settingsWrapper.addView(settingsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val settingsFooter = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPaddingMd(UiTokens.SPACE_6, UiTokens.SPACE_0, UiTokens.SPACE_6, UiTokens.SPACE_0)
        }
        settingsFooter.addView(TextView(activity).apply {
            text = "TermLou v${try { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName } catch (_: Exception) { "1.3.1" }}"
            setTextColor(scope.cOnSurfaceVariant)
            textMd(UiTokens.TEXT_META, UiTokens.LINE_META)
            gravity = Gravity.CENTER
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_2, UiTokens.SPACE_0, UiTokens.SPACE_1)
        })
        settingsFooter.addView(TextView(activity).apply {
            val ss = android.text.SpannableString("Made by Lou with ♥")
            ss.setSpan(android.text.style.ForegroundColorSpan(Color.RED), ss.indexOf("♥"), ss.length, 0)
            text = ss
            setTextColor(scope.cOnSurfaceVariant)
            textMd(UiTokens.TEXT_META, UiTokens.LINE_META)
            gravity = Gravity.CENTER
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_2)
        })
        settingsWrapper.addView(settingsFooter)
    }
private fun divider(): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            setMargins(0, 24, 0, 24)
        }
        setBackgroundColor(scope.cOutline)
    }

    /** 成对按钮行的外距：间距走 topMargin（见 shell 行注释），边框只圈按钮条。 */
    private fun pairRowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = activity.dp(UiTokens.SPACE_2)
        }

    /**
     * 5.9.40 新增：成对按钮（保存/重置…）的唯一构造入口。
     *
     * 此前同一段"白字 + TEXT_BODY + `setPaddingMd(UiTokens.SPACE_4, UiTokens.SPACE_2, UiTokens.SPACE_4, UiTokens.SPACE_2)` + 主色胶囊"的代码
     * 在本文件里**被复制了 9 遍**。9 份拷贝意味着 9 个可以各自漂移的真相来源 ——
     * 这就是"同一个 App 里设置项看起来参差不齐"的直接原因。
     *
     * 收敛到这里之后：字号、行高、内距、外距、圆角只有一处可改，
     * 以后再想漂移都漂不动。
     */
    private fun actionButton(text: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(activity).apply {
            this.text = text
            setTextColor(if (primary) Color.WHITE else scope.cOnSurface)
            textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
            // MD3 4dp 网格：原 6 上调到 8（SPACE_2）
            setPaddingMd(UiTokens.SPACE_4, UiTokens.SPACE_2, UiTokens.SPACE_4, UiTokens.SPACE_2)
            if (primary) ButtonStyle.apply(this, scope.cPrimary, scope.cPrimaryVisible)
            else ButtonStyle.apply(this, scope.cOutline)
            setOnClickListener { onClick() }
        }

    /** 输入框的规范内距（此前各处 12/8 与 12/10 并存）。 */
    private fun EditText.applyMdFieldPadding() {
        setPaddingMd(UiTokens.SPACE_3, UiTokens.SPACE_2, UiTokens.SPACE_3, UiTokens.SPACE_2)
    }

    /**
     * 小节标题。
     *
     * 5.9.40 修"A1：组内文字垂直居中"：
     *
     * 此前每一组都是"标题 + 说明"两行小字**顶着 0 上边距**开始，而下面是一排**高按钮**，
     * 于是文字全挤在组顶部、按钮沉在底部 —— 整组视觉重心偏上，像表格里内容靠上对齐。
     *
     * 做法（只动组内，**不碰组与组之间的分割线留白**）：
     * - 小标题（body=true，组内子项）**上方**给 8dp：文字块下沉，不再贴着上一项；
     * - 同时下方从 4dp 收到 2dp：上下匀开，文字块在组内居中；
     * - 大标题（body=false，紧跟分割线的段首）**不加**上方留白 —— 那里本来就该贴着线。
     */
    private fun sectionTitle(parent: LinearLayout, text: String, body: Boolean = false) {
        val tv = TextView(activity).apply {
            this.text = text
            setTextColor(scope.cOnSurface)
            // 5.9.40：MD3 —— 字号走规范档位，行高一并设上（全项目此前 0 次设过行高）
            textMd(
                if (body) UiTokens.TEXT_BODY else UiTokens.TEXT_TITLE,
                if (body) UiTokens.LINE_BODY else UiTokens.LINE_TITLE,
                bold = true,
            )
            setPaddingMd(0, 0, 0, UiTokens.SPACE_1)
        }
        if (body) {
            // 组内子项：上方留白让文字块下沉，与下面的高按钮视觉居中
            parent.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = activity.dp(UiTokens.SPACE_2)
            })
        } else {
            parent.addView(tv)
        }
    }

    private fun buildDisplaySection(parent: LinearLayout, density: Float) {
        sectionTitle(parent, activity.getString(R.string.settings_ui_title))
        buildFontSection(parent, density)
        buildLanguageRow(parent)
        buildWorkshopRow(parent, activity.getString(R.string.settings_palette_title), R.string.palette_open) {
            overlay.openPaletteMaker()
        }
    }

    private fun buildFontSection(parent: LinearLayout, density: Float) {
        sectionTitle(parent, activity.getString(R.string.settings_font_title), body = true)
        val slider = TickSlider(ContextThemeWrapper(activity, R.style.Theme_TermLou_Slider)).apply {
            valueFrom = 0f
            valueTo = 4f
            stepSize = 0f
            value = terminal.fontSizeIndex.toFloat()
            thumbTintList = ColorStateList.valueOf(scope.cPrimaryVisible)
            trackActiveTintList = ColorStateList.valueOf(scope.cPrimaryVisible)
            trackInactiveTintList = ColorStateList.valueOf(scope.cOutline)
            haloTintList = ColorStateList.valueOf((scope.cPrimaryVisible and 0x00FFFFFF) or (0x33 shl 24))
            setTickColors(scope.cPrimaryVisible, (scope.cOutline and 0x00FFFFFF) or (0x66 shl 24))
        }
        parent.addView(slider)
        terminal.bindFontSlider(slider)
    }

    private fun buildShellSection(parent: LinearLayout) {
        sectionTitle(parent, activity.getString(R.string.settings_shell_title), body = true)
        val shellEdit = EditText(activity).apply {
            setText(scope.settingsManager.shellCmd)
            setPaddingMd(UiTokens.SPACE_3, UiTokens.SPACE_2, UiTokens.SPACE_3, UiTokens.SPACE_2)
            setHint(activity.getString(R.string.hint_shell_cmd))
        }.also {
            FieldStyle.applyOutlined(it, scope.theme, UiTokens.TEXT_BODY)
        }
        parent.addView(shellEdit)
        // 根因记录：行间距必须用 topMargin，不能用顶部 padding——胶囊边框圈的是整个
        // 含 padding 的盒子，padding 会把上沿顶到输入框描边上并造成填充错位。
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0)
            addView(actionButton(activity.getString(R.string.save), primary = true) {
                val cmd = shellEdit.text.toString().trim()
                scope.settingsManager.setShellCmd(cmd)
                status.showTempStatus(activity.getString(R.string.shell_saved))
                activity.hideIme()
            }.also { b ->
                b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = activity.dp(UiTokens.SPACE_1)
                }
            })
            addView(actionButton(activity.getString(R.string.reset), primary = false) {
                shellEdit.setText("")
                scope.settingsManager.setShellCmd("")
                status.showTempStatus(activity.getString(R.string.shell_inited_idle))
                activity.hideIme()
            }.also { b ->
                b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = activity.dp(UiTokens.SPACE_1) 
                }
            })
            SegmentStyle.applyRow(
                this,
                listOf(
                    null,
                    null
                ),
                scope.cOutline,
                scope.cOnSurface
            )
        }, pairRowParams())
    }

    private fun buildTileSection(parent: LinearLayout) {
        sectionTitle(parent, activity.getString(R.string.settings_tile_title), body = true)
        val tileEdit = EditText(activity).apply {
            setText(scope.settingsManager.tileCommand)
            setPaddingMd(UiTokens.SPACE_3, UiTokens.SPACE_2, UiTokens.SPACE_3, UiTokens.SPACE_2)
            setHint(activity.getString(R.string.hint_tile_cmd))
        }.also {
            FieldStyle.applyOutlined(it, scope.theme, UiTokens.TEXT_BODY)
        }
        parent.addView(tileEdit)
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0)
            addView(actionButton(activity.getString(R.string.save), primary = true) {
                    val cmd = tileEdit.text.toString().trim()
                    scope.settingsManager.setTileCommand(cmd)
                    status.showTempStatus(activity.getString(R.string.tile_cmd_saved))
                    activity.hideIme()

}.also { b ->
                b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = activity.dp(UiTokens.SPACE_1)
                }
            })
            addView(actionButton(activity.getString(R.string.reset), primary = false) {
                tileEdit.setText(scope.settingsManager.shellCmd)
                scope.settingsManager.setTileCommand(scope.settingsManager.shellCmd)
                status.showTempStatus(activity.getString(R.string.inited))
                activity.hideIme()
            }.also { b ->
                b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = activity.dp(UiTokens.SPACE_1) 
                }
            })
            SegmentStyle.applyRow(
                this,
                listOf(
                    null,
                    null
                ),
                scope.cOutline,
                scope.cOnSurface
            )
        }, pairRowParams())
    }

    private fun buildQuickSection(parent: LinearLayout) {
        sectionTitle(parent, activity.getString(R.string.settings_quick_title))
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0)
            addView(actionButton(activity.getString(R.string.pick_app), primary = true) {
                this@SettingsUiController.overlay.showAppPicker()
            }.also { b ->
                b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = activity.dp(UiTokens.SPACE_1) 
                }
            })
            addView(actionButton(activity.getString(R.string.reset), primary = false) {
                this@SettingsUiController.overlay.onQuickInitClick()
            }.also { b ->
                b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = activity.dp(UiTokens.SPACE_1) 
                }
            })
            SegmentStyle.applyRow(
                this,
                listOf(
                    null,
                    null
                ),
                scope.cOutline,
                scope.cOnSurface
            )
        })
    }

    private fun buildWorkshopRow(
        parent: LinearLayout,
        title: String,
        labelRes: Int = R.string.open_workshop,
        onOpen: () -> Unit
    ) {
        sectionTitle(parent, title, body = true)
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(activity).apply {
                text = activity.getString(labelRes)
                setTextColor(scope.cPrimaryVisible)
                textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
                setPaddingMd(UiTokens.SPACE_4, UiTokens.SPACE_2, UiTokens.SPACE_4, UiTokens.SPACE_2)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                ButtonStyle.outlined(this, scope.cPrimaryVisible)
                setOnClickListener { onOpen() }
            })
        })
    }

    private fun buildUpstreamSection(parent: LinearLayout, density: Float) {
        sectionTitle(parent, activity.getString(R.string.settings_advanced))
        sectionTitle(parent, activity.getString(R.string.upstream_title), body = true)
        val upEdit = EditText(activity).apply {
            setText(scope.settingsManager.netUpstream())
            setHint("socks5://127.0.0.1:1080")
            setSingleLine(true)
            setPaddingMd(12, 10, 12, 10)
        }.also {
            FieldStyle.applyOutlined(it, scope.theme, UiTokens.TEXT_BODY)
        }
        parent.addView(upEdit)
        parent.addView(buildUpstreamButtons(upEdit, density), pairRowParams())
    }

    private fun buildUpstreamButtons(upEdit: EditText, density: Float): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0)
            addView(actionButton(activity.getString(R.string.save), primary = true) {
                scope.settingsManager.setNetUpstream(upEdit.text.toString().trim())
                status.showTempStatus(activity.getString(R.string.upstream_saved))
                activity.hideIme()
            }.also { b ->
                b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = activity.dp(UiTokens.SPACE_1) 
                }
            })
            addView(actionButton(activity.getString(R.string.clear), primary = false) {
                upEdit.setText("")
                scope.settingsManager.setNetUpstream("")
                status.showTempStatus(activity.getString(R.string.upstream_cleared))
                activity.hideIme()
            }.also { b ->
                b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = activity.dp(UiTokens.SPACE_1) 
                }
            })
            SegmentStyle.applyRow(
                this,
                listOf(
                    null,
                    null
                ),
                scope.cOutline,
                scope.cOnSurface
            )
        }

    private fun buildKeepAliveRow(parent: LinearLayout) {
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.keepalive_title)
                setTextColor(scope.cOnSurface)
                textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY, bold = true)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val keepAliveSw = MaterialSwitch(ContextThemeWrapper(activity, R.style.Theme_TermLou_Switch)).apply {
                // 根因：AppCompat/MaterialComponents 主题无 materialSwitchStyle，SwitchCompat 的
                // showText 代码默认 true + textOn/textOff 全 null 会在 onMeasure 里 StaticLayout(null) 崩溃。
                // 开关文案由独立 TextView 承担，这里永远不需要 ON/OFF 字。
                showText = false
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
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            // 5.9.40（A1）：这一行紧跟在浏览器按钮行之后，同样会贴太近，
            // 给它组内子项一致的上方留白。
            topMargin = activity.dp(UiTokens.SPACE_2)
        })
    }

    /** 5.9.65：笔记域设置——空笔记自动删除开关（默认关）。
     *  5.9.66：说明统一写在设置 Tab 的说明页（TabIntroContent 第 4 页）；设置页只留「标题 + 开关」。 */
    private fun buildNotesSection(parent: LinearLayout) {
        sectionTitle(parent, activity.getString(R.string.settings_notes_title))
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.settings_note_autodelete_title)
                setTextColor(scope.cOnSurface)
                textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY, bold = true)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(MaterialSwitch(ContextThemeWrapper(activity, R.style.Theme_TermLou_Switch)).apply {
                showText = false
                elevation = 8f
                thumbTintList = controlTint()
                trackTintList = switchTrackTint()
                isChecked = scope.settingsManager.autoDeleteEmptyNote()
                setOnCheckedChangeListener { _, isChecked ->
                    if (suppressSwitch) return@setOnCheckedChangeListener
                    scope.settingsManager.setAutoDeleteEmptyNote(isChecked)
                }
            })
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

private fun buildLanguageRow(parent: LinearLayout) {
        val langZh = AppLang.isChinese(activity)
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_1)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.lang_title) + " " + if (langZh) "🇨🇳" else "🇺🇸"
                setTextColor(scope.cOnSurface)
                typeface = Typeface.DEFAULT_BOLD
                textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(MaterialSwitch(ContextThemeWrapper(activity, R.style.Theme_TermLou_Switch)).apply {
                showText = false
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
        intArrayOf(scope.cPrimaryVisible, scope.cOutline)
    )

    private fun switchTrackTint(): ColorStateList {
        val checked = (scope.cPrimaryVisible and 0x00FFFFFF) or (0x66 shl 24)
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
        web.refreshRow()
        if (!batteryOptPending) return
        batteryOptPending = false
        val pm = activity.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(activity.packageName)) {
            status.showTempStatus(activity.getString(R.string.battery_exempted))
        }
    }
}
