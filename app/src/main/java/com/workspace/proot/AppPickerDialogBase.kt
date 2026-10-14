package com.workspace.proot

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.res.ColorStateList
import android.view.ContextThemeWrapper
import com.google.android.material.materialswitch.MaterialSwitch

private const val COLOR_MASK_RGB = 0x00FFFFFF
private const val COLOR_ALPHA = 0x66
private const val ALPHA_SHIFT = 24

/**
 * 应用选择弹窗的共用外壳（5.9.51 重构，纯移动：主体逐字取自原 `AppPickerDialog`）。
 *
 * ## 为什么要拆
 *
 * `AppPickerDialog` 与 `NetAppPickerDialog` 原本是两份 178 行的文件，
 * 逐行比对只有 **5 处**不同（git 一度把它们判为同一个文件的重命名）。
 * 同一份布局、同一份渲染、同一份搜索/系统应用过滤逻辑被抄了两遍 ——
 * 改一处外观就要记得改两处，漏一处就是两个弹窗长得不一样。
 *
 * ## 5 处差异如何收敛（**行为逐一对齐，不改任何逻辑**）
 *
 * | 差异点 | 收藏夹版 `AppPickerDialog` | 抓包版 `NetAppPickerDialog` | 本文件的做法 |
 * |---|---|---|---|
 * | 标题 | `R.string.pick_app` | `R.string.picker_capture_title` | 构造参数 `titleRes` |
 * | 初始勾选 | `loadFavoriteApps().associateBy{pkg}.keys` | `loadCaptureApps()` | 构造参数 `selected`（在子类构造时算好，时机与原来完全一致） |
 * | 勾选落盘 | **点了就存**（`addFavoriteApp`/`removeFavoriteApp`） | **点了只改内存**，确定时才 `saveCaptureApps` | `onTogglePersist` 钩子，收藏夹版覆写、抓包版留空实现 |
 * | 确定按钮 | 只 `dismiss()` | 先 `saveCaptureApps` + `onChanged(size)`，再 `dismiss()` | `onDone` 钩子（**仍在 dismiss 之前调用**） |
 * | 构造函数 | 6 个参数 | 多一个 `onChanged` | 两个子类的签名**原样保留**，调用方零改动 |
 *
 * 集合的增删仍留在共用代码里（收藏夹版那段的原有结构），只把"往存储里写"抽出去 ——
 * 所以抓包版"只改内存"的语义没有被改成"也落盘"。
 *
 * ## 锁
 *
 * 两个子类构造函数签名由 `UiDimsScanTest` 之外的调用点保证（`OverlayCommandsController` /
 * `NetworkController`），任何签名改动都会在那两处编译失败。
 */
open class AppPickerDialogBase(
    protected val ctx: Activity,
    protected val accent: Int,
    protected val onSurfaceVariant: Int,
    protected val all: List<AppEntry>,
    protected val sm: SettingsManager,
    protected val theme: ThemeColors,
    private val titleRes: Int,
    protected val selected: MutableSet<String>
) {
    private lateinit var listInner: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var sysSwitch: MaterialSwitch

    /** 勾选变化时是否立刻落盘。默认不落盘（抓包版语义）。 */
    protected open fun onTogglePersist(pkg: String, label: String, nowSelected: Boolean) {}

    /** 点"确定"时、在 `dismiss()` 之前调用。默认什么都不做（收藏夹版语义）。 */
    protected open fun onDone(selected: Set<String>) {}

    fun show() {
        val density = ctx.resources.displayMetrics.density
        // 5.9.40：原 20dp 不在 4dp 网格上，按 MD3 上调到 24dp（SPACE_5）
        val pad = ctx.dp(UiTokens.SPACE_5)

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(UiTokens.SPACE_5, UiTokens.SPACE_2, UiTokens.SPACE_5, UiTokens.SPACE_0)
        }

        content.addView(TextView(ctx).apply {
            text = ctx.getString(titleRes)
            setTextColor(theme.onSurface)
            textMd(UiTokens.TEXT_TITLE, UiTokens.LINE_TITLE)
            setPaddingMd(0, 0, 0, 10)
        })

        searchInput = EditText(ctx).apply {
            hint = ctx.getString(R.string.picker_search)
            textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
            setSingleLine(true)
            setPaddingMd(UiTokens.SPACE_5, UiTokens.SPACE_2, UiTokens.SPACE_5, UiTokens.SPACE_2)
            // 5.9.43：原 setBackgroundColor 是纯色方块（无圆角），改走 FieldStyle 统一外观
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_BODY)
        }
        content.addView(searchInput)

        sysSwitch = MaterialSwitch(ContextThemeWrapper(ctx, R.style.Theme_TermLou_Switch)).apply {
            // 同 SettingsUiController：主题无 materialSwitchStyle 时 showText 默认为 true 会 StaticLayout(null) 崩溃。
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf(accent, onSurfaceVariant)
            )
            trackTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf((accent and COLOR_MASK_RGB) or (COLOR_ALPHA shl ALPHA_SHIFT), (onSurfaceVariant and COLOR_MASK_RGB) or (COLOR_ALPHA shl ALPHA_SHIFT))
            )
            isChecked = sm.includeSystemApps()
        }
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingMd(0, 6, 0, 6)
            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.picker_system)
                setTextColor(onSurfaceVariant)
                textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(sysSwitch)
        })

        val listScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.heightPixels * 0.45f).toInt()
            )
        }
        listInner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        listScroll.addView(listInner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(listScroll)

        val doneBtn = Button(ctx).apply {
            text = ctx.getString(R.string.done)
            setTextColor(accent)
            textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
            isAllCaps = true
            ButtonStyle.outlined(this, accent)
        }
        content.addView(doneBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (12 * density).toInt()
            bottomMargin = (20 * density).toInt()
        })

        val dialog = AlertDialog.Builder(ctx)
            .setView(content)
            .create()
        doneBtn.setOnClickListener {
            onDone(selected)
            dialog.dismiss()
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { render() }
        })
        sysSwitch.setOnCheckedChangeListener { _, c -> sm.setIncludeSystemApps(c); render() }
        render()
        dialog.show()
        DialogStyler.apply(dialog, theme)
    }

    private fun render() {
        val q = searchInput.text?.toString()?.trim().orEmpty()
        val sys = sysSwitch.isChecked
        val filtered = all.filter { (sys || !it.system) && (q.isEmpty() || it.label.contains(q, true)) }
            .sortedWith(compareBy({ !selected.contains(it.pkg) }, { it.label.lowercase() }))
        listInner.removeAllViews()
        val density = ctx.resources.displayMetrics.density
        for (entry in filtered) {
            val isSel = selected.contains(entry.pkg)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingMd(12, 0, 12, 0)
            }
            val tick = TextView(ctx).apply {
                text = if (isSel) ctx.getString(R.string.tick) else ""
                setTextColor(accent)
                textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
                setPaddingMd(0, 0, 6, 0)
            }
            val name = TextView(ctx).apply {
                text = entry.label
                setTextColor(theme.onSurface)
                textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
                setSingleLine(true)
            }
            row.addView(tick)
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener {
                if (selected.contains(entry.pkg)) {
                    selected.remove(entry.pkg)
                    onTogglePersist(entry.pkg, entry.label, false)
                } else {
                    selected.add(entry.pkg)
                    onTogglePersist(entry.pkg, entry.label, true)
                }
                render()
            }
            listInner.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (46 * density).toInt()
            ))
        }
    }
}