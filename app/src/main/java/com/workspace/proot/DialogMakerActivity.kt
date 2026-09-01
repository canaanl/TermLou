package com.workspace.proot

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
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
class DialogMakerActivity : Activity() {

    private val theme = ThemeColors.default()

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

    private sealed class Ctrl {
        data class TextOutput(val note: String = "运行时输出区") : Ctrl()
        data class Input(val label: String, val key: String) : Ctrl()
        data class Select(val label: String, val key: String, val options: List<String>, val multi: Boolean) : Ctrl()
        data class Toggle(val label: String, val key: String) : Ctrl()
        data class Buttons(val buttons: List<ScriptDialogSpec.Button>) : Ctrl()
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
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        }
        titleBar.addView(TextView(this).apply {
            text = "弹窗工坊"
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_TITLE
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
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }
        scroll.addView(form)
        root.addView(scroll)

        // ===== 标题 / 正文 =====
        titleEdit = sectionEdit(form, "标题", "显示在浮窗顶栏")
        messageRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        messageEdit = EditText(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(theme.primaryContainer)
            textSize = UiTokens.TEXT_BODY
            setHint("正文（可留空）")
            setHintTextColor(theme.onSurfaceVariant)
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            gravity = Gravity.TOP
            minLines = 2
        }
        messageRow.addView(messageEdit)
        form.addView(sectionLabel("正文"))
        form.addView(messageRow)

        // ===== 主题 / 位置 / 动画 =====
        themeGroup = sectionRadio(form, "主题", listOf("dark", "light", "glass"), "dark")
        positionGroup = sectionRadio(form, "位置", listOf("center", "bottom"), "center")
        animGroup = sectionRadio(form, "动画", listOf("scale", "fade", "slide-up"), "scale")

        // ===== 主色 =====
        form.addView(sectionLabel("主色（点击色块；不选则用主题默认）"))
        accentSwatches = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (8 * d).toInt())
        }
        form.addView(accentSwatches)
        rebuildSwatches()

        // ===== 圆角 =====
        form.addView(sectionLabel("圆角"))
        val radiusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        radiusSeek = SeekBar(this).apply {
            max = 32
            progress = ScriptDialogSpec.DEFAULT_RADIUS_DP
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        radiusLabel = TextView(this).apply {
            text = "${radiusSeek.progress}dp"
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_COMPACT
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * d).toInt(), 0, 0, 0)
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
        form.addView(sectionLabel("控件"))
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
            text = "实时预览"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_TITLE
            setPadding(0, 0, 0, (8 * d).toInt())
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
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            setBackgroundColor(theme.surfaceVariant)
        }
        val testBtn = Button(this).apply {
            text = "真机测试浮窗"
            setTextColor(Color.WHITE)
            isAllCaps = false
            ButtonStyle.apply(this, theme.primary)
            setOnClickListener { testOnScreen() }
        }
        bottomBar.addView(testBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val exportBtn = Button(this).apply {
            text = "导出命令"
            setTextColor(Color.WHITE)
            isAllCaps = false
            ButtonStyle.apply(this, theme.outline)
            setOnClickListener { exportCommand() }
        }
        val exitBtn = Button(this).apply {
            text = "退出"
            setTextColor(Color.WHITE)
            isAllCaps = false
            ButtonStyle.apply(this, theme.outline)
            setOnClickListener { finish() }
        }
        row2.addView(exportBtn, weighted(exportBtn))
        row2.addView(exitBtn, weighted(exitBtn))
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
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        textSize = UiTokens.TEXT_BODY
        setPadding(0, (10 * d()).toInt(), 0, (4 * d()).toInt())
    }

    private fun sectionEdit(container: LinearLayout, label: String, hint: String): EditText {
        container.addView(sectionLabel(label))
        val e = EditText(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(theme.primaryContainer)
            textSize = UiTokens.TEXT_BODY
            setHint(hint)
            setHintTextColor(theme.onSurfaceVariant)
            setPadding((10 * d()).toInt(), (8 * d()).toInt(), (10 * d()).toInt(), (8 * d()).toInt())
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
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_COMPACT
                isChecked = options[i] == default
                setPadding((6 * d()).toInt(), 0, (10 * d()).toInt(), 0)
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
                    cornerRadius = (8 * dd).toFloat()
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
            "文本输出" to { addCtrl(Ctrl.TextOutput()) },
            "文本输入" to { addInputCtrl() },
            "单选" to { addSelectCtrl(multi = false) },
            "多选" to { addSelectCtrl(multi = true) },
            "开关" to { addToggleCtrl() },
            "按钮组" to { addButtonsCtrl() }
        )
        for ((text, action) in labels) {
            val b = Button(this).apply {
                this.text = "+$text"
                setTextColor(Color.WHITE)
                isAllCaps = false
                textSize = UiTokens.TEXT_COMPACT
                setPadding((10 * dd).toInt(), (4 * dd).toInt(), (10 * dd).toInt(), (4 * dd).toInt())
                ButtonStyle.apply(this, theme.outline)
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
            "标签" to "",
            "字段名(key)" to ""
        )) { vals ->
            val label = vals["标签"]?.trim() ?: ""
            val key = vals["字段名(key)"]?.trim()?.ifEmpty { label } ?: label
            if (key.isNotEmpty()) {
                controls.add(Ctrl.Input(label.ifEmpty { key }, key))
                renderCtrlList()
                refreshPreview()
            }
        }
    }

    private fun addSelectCtrl(multi: Boolean) {
        promptFields(mapOf(
            "标签" to "",
            "字段名(key)" to "",
            "选项(逗号分隔)" to "a,b,c"
        )) { vals ->
            val label = vals["标签"]?.trim() ?: ""
            val key = vals["字段名(key)"]?.trim()?.ifEmpty { label } ?: label
            val opts = vals["选项(逗号分隔)"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            if (key.isNotEmpty() && opts.isNotEmpty()) {
                controls.add(Ctrl.Select(label.ifEmpty { key }, key, opts, multi))
                renderCtrlList()
                refreshPreview()
            }
        }
    }

    private fun addButtonsCtrl() {
        promptFields(mapOf(
            "按钮(逗号分隔, 文本=id=类型)" to "确定=ok=primary,取消=cancel=normal"
        )) { vals ->
            val raw = vals["按钮(逗号分隔, 文本=id=类型)"] ?: ""
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
            "标签" to "",
            "字段名(key)" to ""
        )) { vals ->
            val label = vals["标签"]?.trim() ?: ""
            val key = vals["字段名(key)"]?.trim()?.ifEmpty { label } ?: label
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
            setPadding((20 * d).toInt(), 0, (20 * d).toInt(), 0)
        }
        val edits = mutableMapOf<String, EditText>()
        for ((label, def) in fields) {
            container.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_COMPACT
                setPadding(0, (8 * d).toInt(), 0, (2 * d).toInt())
            })
            val e = EditText(this).apply {
                setText(def)
                setTextColor(Color.WHITE)
                setSingleLine(true)
            }
            edits[label] = e
            container.addView(e)
        }
        AlertDialog.Builder(this)
            .setTitle("添加控件")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                onOk(edits.mapValues { it.value.text.toString() })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderCtrlList() {
        ctrlList.removeAllViews()
        val dd = d()
        for (i in controls.indices) {
            val c = controls[i]
            val label = when (c) {
                is Ctrl.TextOutput -> "文本输出: ${c.note}"
                is Ctrl.Input -> "文本输入: ${c.label}"
                is Ctrl.Select -> (if (c.multi) "多选" else "单选") + ": ${c.label}"
                is Ctrl.Toggle -> "开关: ${c.label}"
                is Ctrl.Buttons -> "按钮组: ${c.buttons.joinToString { it.text }}"
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(theme.primaryContainer)
                setPadding((10 * dd).toInt(), (8 * dd).toInt(), (10 * dd).toInt(), (8 * dd).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * dd).toInt() }
            }
            row.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = UiTokens.TEXT_COMPACT
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "×"
                setTextColor(Color.WHITE)
                isAllCaps = false
                textSize = UiTokens.TEXT_COMPACT
                setPadding((8 * dd).toInt(), 0, (8 * dd).toInt(), 0)
                setOnClickListener {
                    controls.removeAt(i)
                    renderCtrlList()
                    refreshPreview()
                }
            })
            ctrlList.addView(row)
        }
    }

    // ===== 组装 Request =====

    private fun buildRequest(id: String): ScriptDialogSpec.Request {
        val rows = mutableListOf<ScriptDialogSpec.Row>()
        for (c in controls) {
            when (c) {
                is Ctrl.TextOutput -> rows.add(ScriptDialogSpec.Row.Text(ScriptDialogSpec.TextRow(c.note)))
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
            }
        }
        if (controls.none { it is Ctrl.Buttons }) {
            rows.add(ScriptDialogSpec.Row.Buttons(ScriptDialogSpec.ButtonsRow(
                listOf(ScriptDialogSpec.Button("关闭", "close"))
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
        val card = renderer.buildCard(buildRequest("preview")) {}
        previewHost.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    // ===== 底部操作 =====

    private fun testOnScreen() {
        if (!Settings.canDrawOverlays(this)) {
            Snackbar.make(root, "需要悬浮窗权限", Snackbar.LENGTH_SHORT).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
            )
            return
        }
        val overlay = ScriptDialogOverlay(this, buildRequest("test")) {}
        overlay.show()
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

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
        val sb = StringBuilder("termlou-ui")
        for (p in parts) sb.append(" \\\n  ").append(p)
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("termlou-ui", sb.toString()))
        Snackbar.make(root, "命令已复制到剪贴板", Snackbar.LENGTH_SHORT).show()
    }
}
