package com.workspace.proot

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar

/** 弹窗工坊：图形化设计脚本弹窗，实时预览，导出命令/真机测试。 */
@Suppress("LargeClass")
class DialogMakerActivity : Activity() {

    companion object {
        private const val REQ_CANVAS_DESIGNER = 4911
    }

    private val theme: ThemeColors by lazy {
        PaletteStore.theme(getSharedPreferences("term-lou-settings", MODE_PRIVATE))
    }

    private lateinit var root: LinearLayout
    private lateinit var titleEdit: EditText
    private lateinit var messageEdit: EditText
    private lateinit var themeGroup: RadioGroup
    private lateinit var positionGroup: RadioGroup
    private lateinit var animGroup: RadioGroup
    private lateinit var radiusSeek: SeekBar
    private lateinit var radiusLabel: TextView
    private lateinit var accentSwatches: LinearLayout
    private lateinit var previewHost: FrameLayout
    private lateinit var ctrlList: LinearLayout
    private lateinit var messageRow: LinearLayout

    private var accent: Int? = null
    private val controls = mutableListOf<Ctrl>()
    private var editingCanvasIndex: Int? = null

    private sealed class Ctrl {
        data class TextOutput(val note: String = "") : Ctrl()
        data class Input(val label: String, val key: String) : Ctrl()
        data class Select(val label: String, val key: String, val options: List<String>, val multi: Boolean) : Ctrl()
        data class Toggle(val label: String, val key: String) : Ctrl()
        data class Buttons(val buttons: List<ScriptDialogSpec.Button>) : Ctrl()
        data class Canvas(val scene: CanvasScene, val heightDp: Int = 220, val key: String = "") : Ctrl()
    }

    private val accentColors = listOf(
        0xFF2D7D46.toInt(), 0xFF0E639C.toInt(), 0xFF4DD0E1.toInt(),
        0xFFFFD54F.toInt(), 0xFFFF5252.toInt(), 0xFFB388FF.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.surfaceVariant)
            setPaddingMd(16, 12, 16, 12)
        }
        titleBar.addView(TextView(this).apply {
            text = getString(R.string.dm_title)
            setTextColor(theme.onSurface)
            textMd(UiTokens.TEXT_TITLE, UiTokens.LINE_TITLE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(titleBar)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(16, 8, 16, 8)
        }
        scroll.addView(form)
        root.addView(scroll)

        // ===== 标题 / 正文 =====
        titleEdit = sectionEdit(form, getString(R.string.dm_section_title), getString(R.string.dm_title_hint))
        messageRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        messageEdit = EditText(this).apply {
            setTextColor(theme.onSurface)
            // 5.9.50：原为 setBackgroundColor（纯色方块 = 直角）。此前这是一条**有记录的
            // 豁免**，理由是"它是模拟 agent 弹窗输入框的预览框，直角才是它该有的样子"。
            // 但那个理由早已不成立：真实渲染（ScriptDialogRenderer L325/352/463/561/600/615）
            // 的弹窗输入框全部是 snapCorner(CORNER_SM) 圆角 —— 真弹窗圆了，只有这个模拟框
            // 还留着直角，模拟反而失了真。圆角取 CORNER_SM 与真实渲染一致（真实那边只有
            // 圆角无描边，这里同样不加描边）；填充色保持 primaryContainer 不变。
            roundedBg(theme.primaryContainer, UiTokens.CORNER_SM)
            textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
            setHint(getString(R.string.dm_msg_hint))
            setHintTextColor(theme.onSurfaceVariant)
            setPaddingMd(10, 8, 10, 8)
            gravity = Gravity.TOP
            minLines = 2
        }
        messageRow.addView(messageEdit)
        form.addView(sectionLabel(getString(R.string.dm_section_msg)))
        form.addView(messageRow)

        // ===== 主题 / 位置 / 动画 =====
        themeGroup = sectionRadio(form, getString(R.string.dm_section_theme), listOf("dark", "light", "glass"), "dark")
        positionGroup = sectionRadio(form, getString(R.string.dm_section_pos), listOf("center", "bottom"), "center")
        animGroup = sectionRadio(form, getString(R.string.dm_section_anim), listOf("scale", "fade", "slide-up"), "scale")

        // ===== 主色 =====
        form.addView(sectionLabel(getString(R.string.dm_accent_hint)))
        accentSwatches = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingMd(0, 0, 0, 8)
        }
        form.addView(accentSwatches)
        rebuildSwatches()

        // ===== 圆角 =====
        form.addView(sectionLabel(getString(R.string.dm_section_radius)))
        val radiusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        radiusSeek = SeekBar(this).apply {
            max = 32
            progress = ScriptDialogSpec.DEFAULT_RADIUS_DP
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        radiusLabel = TextView(this).apply {
            text = "${radiusSeek.progress}dp"
            setTextColor(theme.onSurface)
            textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
            gravity = Gravity.CENTER_VERTICAL
            setPaddingMd(10, 0, 0, 0)
        }
        radiusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                radiusLabel.text = "$p dp"
                if (fromUser) refreshPreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        radiusRow.addView(radiusSeek)
        radiusRow.addView(radiusLabel)
        form.addView(radiusRow)

        // ===== 控件列表 =====
        form.addView(sectionLabel(getString(R.string.dm_section_ctrls)))
        ctrlList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        form.addView(ctrlList)
        form.addView(addCtrlButtons())

        form.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, (16 * d).toInt(), 0, (8 * d).toInt())
            }
            setBackgroundColor(theme.outline)
        })

        // ===== 实时预览 =====
        form.addView(TextView(this).apply {
            text = getString(R.string.dm_preview)
            setTextColor(theme.onSurface)
            typeface = Typeface.DEFAULT_BOLD
            textMd(UiTokens.TEXT_TITLE, UiTokens.LINE_TITLE)
            setPaddingMd(0, 0, 0, 8)
        })
        previewHost = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (420 * d).toInt()
            )
        }
        form.addView(previewHost)

        // ===== 底部按钮（品字：真机测试满行；导出命令+退出在下一行）=====
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(12, 8, 12, 12)
            setBackgroundColor(theme.surfaceVariant)
        }
        val testRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tryBtn = Button(this).apply {
            text = getString(R.string.dm_preview_run)
            setTextColor(Color.WHITE)
            isAllCaps = true
            setOnClickListener { runPreview() }
        }
        val testBtn = Button(this).apply {
            text = getString(R.string.dm_test)
            setTextColor(Color.WHITE)
            isAllCaps = true
            setOnClickListener { testOnScreen() }
        }
        testRow.addView(tryBtn, weighted(tryBtn))
        testRow.addView(testBtn, weighted(testBtn))
        SegmentStyle.applyRow(
            testRow,
            listOf(null, null),
            theme.outline,
            theme.onSurface
        )
        bottomBar.addView(testRow)
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * d).toInt() }
        }
        val exportBtn = Button(this).apply {
            text = getString(R.string.dm_export)
            setTextColor(Color.WHITE)
            isAllCaps = true
            setOnClickListener { exportCommand() }
        }
        val exitBtn = Button(this).apply {
            text = getString(R.string.dm_exit)
            setTextColor(Color.WHITE)
            isAllCaps = true
            setOnClickListener { finish() }
        }
        row2.addView(exportBtn, weighted(exportBtn))
        row2.addView(exitBtn, weighted(exitBtn))
        SegmentStyle.applyRow(
            row2,
            listOf(
                null,
                null
            ),
            theme.outline,
            theme.onSurface
        )
        bottomBar.addView(row2)
        root.addView(bottomBar)

        setContentView(root)
        refreshPreview()
    }

    // ===== helpers: form 构建 =====

    private fun weighted(b: Button): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (4 * d()).toInt()
            marginEnd = (4 * d()).toInt()
        }

    private fun d() = resources.displayMetrics.density

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(theme.onSurface)
        typeface = Typeface.DEFAULT_BOLD
        textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
        setPaddingMd(0, 10, 0, 4)
    }

    private fun sectionEdit(container: LinearLayout, label: String, hint: String): EditText {
        container.addView(sectionLabel(label))
        val e = EditText(this).apply {
            setTextColor(theme.onSurface)
            // 5.9.50：原为 setBackgroundColor（纯色方块 = 直角）。此前这是一条**有记录的
            // 豁免**，理由是"它是模拟 agent 弹窗输入框的预览框，直角才是它该有的样子"。
            // 但那个理由早已不成立：真实渲染（ScriptDialogRenderer L325/352/463/561/600/615）
            // 的弹窗输入框全部是 snapCorner(CORNER_SM) 圆角 —— 真弹窗圆了，只有这个模拟框
            // 还留着直角，模拟反而失了真。圆角取 CORNER_SM 与真实渲染一致（真实那边只有
            // 圆角无描边，这里同样不加描边）；填充色保持 primaryContainer 不变。
            roundedBg(theme.primaryContainer, UiTokens.CORNER_SM)
            textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
            setHint(hint)
            setHintTextColor(theme.onSurfaceVariant)
            setPaddingMd(10, 8, 10, 8)
        }
        container.addView(e)
        return e
    }

    private fun sectionRadio(
        container: LinearLayout,
        label: String,
        options: List<String>,
        default: String
    ): RadioGroup {
        container.addView(sectionLabel(label))
        val rg = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        for (i in options.indices) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = options[i]
                setTextColor(theme.onSurface)
                textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
                androidx.core.widget.CompoundButtonCompat.setButtonTintList(
                    this,
                    ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf(-android.R.attr.state_checked)
                        ),
                        intArrayOf(theme.primaryVisible, theme.onSurfaceVariant)
                    )
                )
                isChecked = options[i] == default
                setPaddingMd(6, 0, 10, 0)
                setOnClickListener { refreshPreview() }
            }
            rg.addView(rb)
        }
        container.addView(rg)
        return rg
    }

    private fun selectedOption(rg: RadioGroup): String? {
        val id = rg.checkedRadioButtonId
        if (id < 0) return null
        val rb = rg.findViewById<RadioButton>(id) ?: return null
        return rb.text.toString()
    }

    // ===== 主色色块 =====

    private fun rebuildSwatches() {
        accentSwatches.removeAllViews()
        val dd = d()
        for (c in accentColors) {
            val selected = accent == c
            val sw = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(c)
                    cornerRadius = snapCorner(UiTokens.CORNER_SM) * dd
                    if (selected) setStroke((2 * dd).toInt(), Color.WHITE)
                }
                layoutParams = LinearLayout.LayoutParams((44 * dd).toInt(), (32 * dd).toInt()).apply {
                    marginEnd = (8 * dd).toInt()
                }
                setOnClickListener {
                    accent = if (accent == c) null else c
                    rebuildSwatches()
                    refreshPreview()
                }
            }
            accentSwatches.addView(sw)
        }
    }

    // ===== 控件增删 =====

    private fun addCtrl(c: Ctrl) {
        controls.add(c)
        renderCtrlList()
        refreshPreview()
    }

    private fun addCtrlButtons(): View {
        val dd = d()
        val wrap = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = mapOf(
            getString(R.string.dm_ctrl_text_out) to { addCtrl(Ctrl.TextOutput()) },
            getString(R.string.dm_ctrl_text_in) to { addInputCtrl() },
            getString(R.string.dm_ctrl_single) to { addSelectCtrl(multi = false) },
            getString(R.string.dm_ctrl_multi) to { addSelectCtrl(multi = true) },
            getString(R.string.dm_ctrl_toggle) to { addToggleCtrl() },
            getString(R.string.dm_ctrl_buttons) to { addButtonsCtrl() },
            getString(R.string.dm_ctrl_canvas) to {
                // 单条 termlou-ui 命令只接收一个 --canvas 初始场景；已有 Canvas 时直接
                // 打开编辑，避免设计器里出现多个 Canvas 但导出时只能保留最后一个。
                val existing = controls.indexOfFirst { it is Ctrl.Canvas }
                openCanvasDesigner(if (existing >= 0) existing else null)
            }
        )
        for ((text, action) in labels) {
val b = Button(this).apply {
                this.text = "+$text"
                setTextColor(theme.primaryVisible)
                isAllCaps = true
                textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
                setPaddingMd(10, 4, 10, 4)
                ButtonStyle.outlined(this, theme.primaryVisible)
                setOnClickListener { action() }
            }
            inner.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * dd).toInt() })
        }
        wrap.addView(inner)
        return wrap
    }

    private fun addInputCtrl() {
        promptFields(mapOf(
            getString(R.string.dm_f_label) to "",
            getString(R.string.dm_f_key) to ""
        )) { vals ->
            val label = vals[getString(R.string.dm_f_label)]?.trim() ?: ""
            val key = vals[getString(R.string.dm_f_key)]?.trim()?.ifEmpty { label } ?: label
            if (key.isNotEmpty()) {
                controls.add(Ctrl.Input(label.ifEmpty { key }, key))
                renderCtrlList()
                refreshPreview()
            }
        }
    }

    private fun addSelectCtrl(multi: Boolean) {
        promptFields(mapOf(
            getString(R.string.dm_f_label) to "",
            getString(R.string.dm_f_key) to "",
            getString(R.string.dm_f_opts) to "a,b,c"
        )) { vals ->
            val label = vals[getString(R.string.dm_f_label)]?.trim() ?: ""
            val key = vals[getString(R.string.dm_f_key)]?.trim()?.ifEmpty { label } ?: label
            val opts = vals[getString(R.string.dm_f_opts)]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            if (key.isNotEmpty() && opts.isNotEmpty()) {
                controls.add(Ctrl.Select(label.ifEmpty { key }, key, opts, multi))
                renderCtrlList()
                refreshPreview()
            }
        }
    }

    private fun addButtonsCtrl() {
        promptFields(mapOf(
            getString(R.string.dm_f_btns) to getString(R.string.dm_btns_default)
        )) { vals ->
            val raw = vals[getString(R.string.dm_f_btns)] ?: ""
            val buttons = raw.split(",").mapNotNull { s ->
                val parts = s.trim().split("=")
                if (parts.isEmpty() || parts[0].isEmpty()) null
                else ScriptDialogSpec.Button(
                    parts[0].trim(),
                    parts.getOrNull(1)?.trim()?.ifEmpty { parts[0].trim() } ?: parts[0].trim(),
                    parts.getOrNull(2)?.trim()?.ifEmpty { ScriptDialogSpec.BTN_NORMAL } ?: ScriptDialogSpec.BTN_NORMAL
                )
            }
            if (buttons.isNotEmpty()) {
                controls.add(Ctrl.Buttons(buttons))
                renderCtrlList()
                refreshPreview()
            }
        }
    }

    private fun addToggleCtrl() {
        promptFields(mapOf(
            getString(R.string.dm_f_label) to "",
            getString(R.string.dm_f_key) to ""
        )) { vals ->
            val label = vals[getString(R.string.dm_f_label)]?.trim() ?: ""
            val key = vals[getString(R.string.dm_f_key)]?.trim()?.ifEmpty { label } ?: label
            if (key.isNotEmpty()) {
                controls.add(Ctrl.Toggle(label.ifEmpty { key }, key))
                renderCtrlList()
                refreshPreview()
            }
        }
    }

    private fun promptFields(fields: Map<String, String>, onOk: (Map<String, String>) -> Unit) {
        val d = d()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(20, 0, 20, 0)
        }
        val edits = mutableMapOf<String, EditText>()
        for ((label, def) in fields) {
            container.addView(TextView(this).apply {
                text = label
                setTextColor(theme.onSurface)
                textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
                setPaddingMd(0, 8, 0, 2)
            })
            val e = EditText(this).apply {
                setText(def)
                setTextColor(theme.onSurface)
                setSingleLine(true)
            }
            edits[label] = e
            container.addView(e)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dm_add_ctrl))
            .setView(container)
            .setPositiveButton(getString(R.string.sc_confirm)) { _, _ ->
                onOk(edits.mapValues { it.value.text.toString() })
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    private fun renderCtrlList() {
        ctrlList.removeAllViews()
        val dd = d()
        for (i in controls.indices) {
            val c = controls[i]
            val label = when (c) {
                is Ctrl.TextOutput -> getString(R.string.dm_summary_out_fmt, c.note.ifBlank { getString(R.string.dm_note_output) })
                is Ctrl.Input -> getString(R.string.dm_summary_in_fmt, c.label)
                is Ctrl.Select -> (if (c.multi) getString(R.string.dm_ctrl_multi) else getString(R.string.dm_ctrl_single)) + ": ${c.label}"
                is Ctrl.Toggle -> getString(R.string.dm_summary_toggle_fmt, c.label)
                is Ctrl.Buttons -> getString(R.string.dm_summary_btns_fmt, c.buttons.joinToString { it.text })
                is Ctrl.Canvas -> getString(R.string.dm_summary_canvas_fmt, c.scene.nodes.size)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 5.9.50：这一行是 LinearLayout 列表行，不是输入框 —— 扫描锁
                // `edit texts use a rounded background` 只看 EditText 块，所以它从来不在
                // 5.9.43 那条豁免的范围里。属同一类缺陷（直角方块），但不在本次范围内，保持原样。
                setBackgroundColor(theme.primaryContainer)
                setPaddingMd(10, 8, 10, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * dd).toInt() }
            }
            row.addView(TextView(this).apply {
                text = label
                setTextColor(theme.onSurface)
                textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "×"
                setTextColor(theme.onSurface)
                isAllCaps = true
                textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
                setPaddingMd(8, 0, 8, 0)
                // 5.9.41：统一圆角来源（此前是 MD2 主题自带圆角）。
                // 浅底按钮：填充用 surfaceVariant + outline 描边，字色沿用 onSurface。
                ButtonStyle.outlined(this, theme.outline)
                setOnClickListener {
                    controls.removeAt(i)
                    renderCtrlList()
                    refreshPreview()
                }
            })
            ctrlList.addView(row)
            if (c is Ctrl.Canvas) {
                row.setOnClickListener { openCanvasDesigner(i) }
            }
        }
    }

    // ===== 组装 Request =====

    private fun buildRequest(id: String): ScriptDialogSpec.Request {
        val rows = mutableListOf<ScriptDialogSpec.Row>()
        for (c in controls) {
            when (c) {
                is Ctrl.TextOutput -> rows.add(ScriptDialogSpec.Row.Text(ScriptDialogSpec.TextRow(c.note.ifBlank { getString(R.string.dm_note_output) })))
                is Ctrl.Input -> rows.add(
                    ScriptDialogSpec.Row.Input(ScriptDialogSpec.InputRow(c.key, c.label))
                )
                is Ctrl.Select -> rows.add(
                    ScriptDialogSpec.Row.Select(ScriptDialogSpec.SelectRow(c.key, c.label, c.options, c.multi))
                )
                is Ctrl.Toggle -> rows.add(
                    ScriptDialogSpec.Row.Toggle(ScriptDialogSpec.ToggleRow(c.key, c.label))
                )
                is Ctrl.Buttons -> rows.add(
                    ScriptDialogSpec.Row.Buttons(ScriptDialogSpec.ButtonsRow(c.buttons))
                )
                is Ctrl.Canvas -> rows.add(
                    ScriptDialogSpec.Row.Canvas(
                        ScriptDialogSpec.CanvasRow(c.scene, c.heightDp, c.key)
                    )
                )
            }
        }
        if (controls.none { it is Ctrl.Buttons }) {
            rows.add(ScriptDialogSpec.Row.Buttons(ScriptDialogSpec.ButtonsRow(
                listOf(ScriptDialogSpec.Button(getString(R.string.close), "close"))
            )))
        }
        return ScriptDialogSpec.Request(
            id = id,
            ui = ScriptDialogSpec.Ui(
                title = titleEdit.text.toString().trim(),
                message = messageEdit.text.toString().trim(),
                rows = rows
            ),
            style = ScriptDialogSpec.Style(
                theme = selectedOption(themeGroup) ?: "dark",
                accent = accent,
                radiusDp = radiusSeek.progress,
                position = selectedOption(positionGroup) ?: "center",
                anim = selectedOption(animGroup) ?: "scale"
            )
        )
    }

    private fun refreshPreview() {
        previewHost.removeAllViews()
        val renderer = ScriptDialogRenderer(this)
        val stage = FrameLayout(this).apply {
            setBackgroundColor(0xFF0B0B0B.toInt())
        }
        // buildRoot 而不是 buildCard：宽度百分比、位置（居中/底部）和动画都要能在工坊里看到。
        stage.addView(
            renderer.buildRoot(buildRequest("preview")) {},
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        previewHost.addView(stage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    /** 权限无关的全屏试运行：真实的卡片宽度/位置/动画，点按会展示将输出的结果 JSON。 */
    private fun runPreview() {
        val request = buildRequest("preview")
        val renderer = ScriptDialogRenderer(this)
        val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0B0B.toInt())
        }

        val top = previewTopBar(renderer, dialog)
        column.addView(top)

        val host = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        column.addView(host)

        val resultView = TextView(this).apply {
            setTextColor(0xFFB9F6CA.toInt())
            textMeta()
            typeface = Typeface.MONOSPACE
            setPaddingMd(12, 6, 12, 10)
            setBackgroundColor(0xFF101010.toInt())
            visibility = View.GONE
            setTextIsSelectable(true)
        }
        column.addView(resultView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0
        ))

        val content = renderer.buildRoot(request) { result ->
            handlePreviewResult(dialog, request, result, resultView)
        }
        host.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        dialog.setContentView(column)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.show()
    }

    private fun previewTopBar(renderer: ScriptDialogRenderer, dialog: Dialog): View {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingMd(12, 8, 12, 8)
            setBackgroundColor(theme.surfaceVariant)
        }
        top.addView(TextView(this).apply {
            text = getString(R.string.dm_preview_hint)
            setTextColor(theme.onSurfaceVariant)
            textMeta()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(previewActionButton(getString(R.string.dm_preview_replay)) {
            renderer.replayCanvasAnimations()
        })
        top.addView(previewActionButton(getString(R.string.dm_preview_close), primary = true) {
            dialog.dismiss()
        })
        return top
    }

    private fun handlePreviewResult(
        dialog: Dialog,
        request: ScriptDialogSpec.Request,
        result: ScriptDialogSpec.Result,
        resultView: TextView
    ) {
        val json = ScriptDialogSpec.resultToJsonString(result)
        resultView.text = json
        resultView.visibility = View.VISIBLE
        resultView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (120 * d()).toInt()
        )
        if (ScriptDialogSpec.shouldDismiss(request, result)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dm_preview_result))
                .setMessage(json)
                .setPositiveButton(getString(R.string.dm_preview_close)) { _, _ ->
                    dialog.dismiss()
                }
                .setNegativeButton(getString(R.string.dm_preview_keep), null)
                .showStyled(theme)
        }
    }

    private fun previewActionButton(label: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = true
            textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
            setPaddingMd(10, 4, 10, 4)
            if (primary) {
                setTextColor(Color.WHITE)
                ButtonStyle.apply(this, theme.primary, theme.primaryVisible)
            } else {
                setTextColor(theme.onSurface)
                ButtonStyle.outlined(this, theme.outline)
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (6 * d()).toInt() }
        }

    // ===== 底部操作 =====

    private fun testOnScreen() {
        if (!Settings.canDrawOverlays(this)) {
            Snackbar.make(root, getString(R.string.dm_need_overlay), Snackbar.LENGTH_SHORT).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
            )
            return
        }
        val overlay = ScriptDialogOverlay(this, buildRequest("test")) {}
        overlay.show()
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun openCanvasDesigner(editIndex: Int?) {
        editingCanvasIndex = editIndex
        val intent = Intent(this, CanvasDesignerActivity::class.java)
        val existing = editIndex?.let { controls.getOrNull(it) as? Ctrl.Canvas }
        if (existing != null) {
            intent.putExtra(
                CanvasDesignerActivity.EXTRA_SCENE,
                CanvasSceneCodec.sceneToJsonString(existing.scene)
            )
        }
        intent.putExtra(CanvasDesignerActivity.EXTRA_HEIGHT, existing?.heightDp ?: 220)
        intent.putExtra(CanvasDesignerActivity.EXTRA_KEY, existing?.key.orEmpty())
        startActivityForResult(intent, REQ_CANVAS_DESIGNER)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CANVAS_DESIGNER) return
        val index = editingCanvasIndex
        editingCanvasIndex = null
        if (resultCode != RESULT_OK) return
        val json = data?.getStringExtra(CanvasDesignerActivity.EXTRA_SCENE) ?: return
        val scene = runCatching { CanvasSceneCodec.parseScene(json) }.getOrNull() ?: return
        val height = data.getIntExtra(CanvasDesignerActivity.EXTRA_HEIGHT, 220).coerceIn(64, 1600)
        val key = data.getStringExtra(CanvasDesignerActivity.EXTRA_KEY).orEmpty()
        if (index != null && index in controls.indices && controls[index] is Ctrl.Canvas) {
            controls[index] = Ctrl.Canvas(scene, height, key)
        } else {
            controls.add(Ctrl.Canvas(scene, height, key))
        }
        renderCtrlList()
        refreshPreview()
    }

    private fun colorHex(c: Int): String = "#%06X".format(c and 0x00FFFFFF)

    private fun exportCommand() {
        val request = buildRequest("x")
        val parts = mutableListOf<String>()
        if (request.ui.title.isNotEmpty()) parts.add("--title ${shellQuote(request.ui.title)}")
        if (request.ui.message.isNotEmpty()) parts.add("--message ${shellQuote(request.ui.message)}")
        parts.add("--theme ${request.style.theme}")
        if (request.style.accent != null) parts.add("--accent ${shellQuote(colorHex(request.style.accent))}")
        parts.add("--radius ${request.style.radiusDp}")
        parts.add("--position ${request.style.position}")
        for (r in request.ui.rows.filterIsInstance<ScriptDialogSpec.Row.Input>()) {
            parts.add("--input ${shellQuote(r.row.label)}")
        }
        for (r in request.ui.rows.filterIsInstance<ScriptDialogSpec.Row.Select>()) {
            parts.add("--${if (r.row.multi) "check" else "select"} ${shellQuote(r.row.label)}")
            for (o in r.row.options) parts.add("--option ${shellQuote(o)}")
        }
        for (r in request.ui.rows.filterIsInstance<ScriptDialogSpec.Row.Toggle>()) {
            parts.add("--toggle ${shellQuote(r.row.label)}")
        }
        for (b in request.ui.rows.filterIsInstance<ScriptDialogSpec.Row.Buttons>().firstOrNull()?.row?.buttons.orEmpty()) {
            parts.add("--button ${shellQuote("${b.text}=${b.id}=${b.kind}")}")
        }
        for (r in request.ui.rows.filterIsInstance<ScriptDialogSpec.Row.Canvas>()) {
            parts.add("--canvas ${shellQuote(CanvasSceneCodec.sceneToJsonString(r.row.scene))}")
            parts.add("--canvas-height ${r.row.heightDp}")
            if (r.row.key.isNotEmpty()) parts.add("--canvas-key ${shellQuote(r.row.key)}")
        }
        val sb = StringBuilder("termlou-ui")
        for (p in parts) sb.append(" \\\n  ").append(p)
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("termlou-ui", sb.toString()))
        Snackbar.make(root, getString(R.string.dm_copied), Snackbar.LENGTH_SHORT).show()
    }
}
