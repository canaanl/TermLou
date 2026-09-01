package com.workspace.proot

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial

class NetAppPickerDialog(
    private val ctx: Activity,
    private val accent: Int,
    private val onSurfaceVariant: Int,
    private val all: List<AppEntry>,
    private val sm: SettingsManager,
    private val onChanged: () -> Unit
) : Dialog(ctx, R.style.FullScreenDialog) {

    private lateinit var card: FrameLayout
    private lateinit var listInner: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var sysSwitch: SwitchMaterial
    private val selected = sm.loadCaptureApps().toMutableSet()

    init {
        window?.apply {
            setLayout(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)
            setWindowAnimations(0)
        }

        val density = ctx.resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.TRANSPARENT) }
        val contentPadH = (60 * density).toInt()
        val contentPadV = (48 * density).toInt()

        card = FrameLayout(ctx).apply {
            alpha = 0f
            setOnClickListener { dismissWithFade() }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(contentPadH, contentPadV, contentPadH, 0)
        }

        content.addView(TextView(ctx).apply {
            text = "选择抓包应用"
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
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
            setOnClickListener {
                sm.saveCaptureApps(selected)
                onChanged()
                dismissWithFade()
            }
        }
        content.addView(doneBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = contentPadV / 2
            bottomMargin = contentPadV / 2
        })

        card.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(card, FrameLayout.LayoutParams(
            ctx.resources.displayMetrics.widthPixels - (16 * density).toInt(),
            (ctx.resources.displayMetrics.heightPixels * 0.76f).toInt(),
            Gravity.CENTER
        ))

        setContentView(root)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { render() }
        })
        sysSwitch.setOnCheckedChangeListener { _, c -> sm.setIncludeSystemApps(c); render() }

        render()
    }

    override fun show() {
        super.show()
        card.post {
            if (card.width > 0 && card.height > 0) {
                applyFrostedCard(card, ctx)
                card.animate().alpha(1f).setDuration(300).start()
            }
        }
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
                if (selected.contains(entry.pkg)) selected.remove(entry.pkg)
                else selected.add(entry.pkg)
                render()
            }
            listInner.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (46 * density).toInt()
            ))
        }
    }

    private fun dismissWithFade() {
        card.animate().alpha(0f).setDuration(200).withEndAction {
            dismiss()
        }.start()
    }
}