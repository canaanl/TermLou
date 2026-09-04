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
import com.google.android.material.switchmaterial.SwitchMaterial

class AppPickerDialog(
    private val ctx: Activity,
    private val accent: Int,
    private val onSurfaceVariant: Int,
    private val all: List<AppEntry>,
    private val sm: SettingsManager,
    private val theme: ThemeColors
) {
    private lateinit var listInner: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var sysSwitch: SwitchMaterial
    private val selected = sm.loadFavoriteApps().associateBy { it.pkg }.keys.toMutableSet()

    fun show() {
        val density = ctx.resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }

        content.addView(TextView(ctx).apply {
            text = "选择应用"
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_TITLE
            setPadding(0, 0, 0, (10 * density).toInt())
        })

        searchInput = EditText(ctx).apply {
            hint = "搜索应用"
            setTextColor(Color.WHITE)
            setHintTextColor(onSurfaceVariant)
            textSize = UiTokens.TEXT_BODY
            setSingleLine(true)
            setPadding(pad, (8 * density).toInt(), pad, (8 * density).toInt())
            setBackgroundColor(UiTokens.searchBg)
        }
        content.addView(searchInput)

        sysSwitch = SwitchMaterial(ctx).apply { isChecked = sm.includeSystemApps() }
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            addView(TextView(ctx).apply {
                text = "包含系统应用"
                setTextColor(onSurfaceVariant)
                textSize = UiTokens.TEXT_COMPACT
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
            text = "完成"
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            isAllCaps = false
            ButtonStyle.apply(this, accent)
        }
        content.addView(doneBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (12 * density).toInt()
        })

        val dialog = AlertDialog.Builder(ctx)
            .setView(content)
            .create()
        doneBtn.setOnClickListener { dialog.dismiss() }
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
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            }
            val tick = TextView(ctx).apply {
                text = if (isSel) "✓" else ""
                setTextColor(accent)
                textSize = UiTokens.TEXT_BODY
                setPadding(0, 0, (6 * density).toInt(), 0)
            }
            val name = TextView(ctx).apply {
                text = entry.label
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_BODY
                setSingleLine(true)
            }
            row.addView(tick)
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener {
                if (selected.contains(entry.pkg)) {
                    selected.remove(entry.pkg)
                    sm.removeFavoriteApp(entry.pkg)
                } else {
                    selected.add(entry.pkg)
                    sm.addFavoriteApp(entry.label, entry.pkg)
                }
                render()
            }
            listInner.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (46 * density).toInt()
            ))
        }
    }
}
