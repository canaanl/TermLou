package com.workspace.proot

import android.app.Activity
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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar

/**
 * 弹窗工坊外壳：标题/主题/外壳样式 + 实时预览 + 导出。
 *
 * 内容区已经全部是 Canvas：线性控件列表和独立 Canvas 控件都已移除，
 * 输入框/选项框/开关/按钮在 CanvasDesigner 的内容画布中编辑。
 */
@Suppress("LargeClass")
class DialogMakerActivity : Activity() {

    companion object {
        private const val REQ_CANVAS_DESIGNER = 4911
        private const val STATE_SCENE = "dialog_content_scene"
    }

    private val theme: ThemeColors by lazy {
        PaletteStore.theme(getSharedPreferences("term-lou-settings", MODE_PRIVATE))
    }

    private lateinit var root: LinearLayout
    private lateinit var titleEdit: EditText
    private lateinit var themeGroup: RadioGroup
    private lateinit var positionGroup: RadioGroup
    private lateinit var animGroup: RadioGroup
    private lateinit var radiusSeek: SeekBar
    private lateinit var radiusLabel: TextView
    private lateinit var accentSwatches: LinearLayout
    private lateinit var previewHost: FrameLayout
    private lateinit var lastResultView: TextView
    private lateinit var canvasSummary: TextView

    private var accent: Int? = null
    private var contentWidthDp = 360f
    private lateinit var contentScene: CanvasScene
    private var previewRenderer: ScriptDialogRenderer? = null
    private var previewRoot: FrameLayout? = null
    private var previewCard: LinearLayout? = null
    private var testOverlay: ScriptDialogOverlay? = null

    private val accentColors = listOf(
        0xFF2D7D46.toInt(), 0xFF0E639C.toInt(), 0xFF4DD0E1.toInt(),
        0xFFFFD54F.toInt(), 0xFFFF5252.toInt(), 0xFFB388FF.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contentWidthDp = dialogContentWidthDp()
        contentScene = savedInstanceState?.getString(STATE_SCENE)
            ?.let { runCatching { CanvasSceneCodec.parseScene(it) }.getOrNull() }
            ?: CanvasScene(width = contentWidthDp, height = 320f)

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

        // ===== 标题 =====
        titleEdit = sectionEdit(form, getString(R.string.dm_section_title), getString(R.string.dm_title_hint))

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

        // ===== 内容画布 =====
        form.addView(sectionLabel(getString(R.string.dm_section_canvas)))
        canvasSummary = TextView(this).apply {
            setTextColor(theme.onSurfaceVariant)
            textCompact()
            setPaddingMd(0, 0, 0, 4)
        }
        form.addView(canvasSummary)
        val editCanvas = Button(this).apply {
            text = getString(R.string.dm_edit_canvas)
            isAllCaps = true
            setTextColor(Color.WHITE)
            setOnClickListener { openCanvasDesigner() }
        }
        ButtonStyle.apply(editCanvas, theme.primary, theme.primaryVisible)
        form.addView(editCanvas, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        form.addView(TextView(this).apply {
            text = getString(R.string.dm_canvas_hint)
            setTextColor(theme.onSurfaceVariant)
            textMeta()
            setPaddingMd(0, 4, 0, 8)
        })

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

        // ===== 调试信息 =====
        form.addView(sectionLabel(getString(R.string.dm_debug_info)))
        lastResultView = TextView(this).apply {
            text = getString(R.string.dm_debug_empty)
            setTextColor(theme.onSurfaceVariant)
            textMeta()
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPaddingMd(10, 8, 10, 8)
            roundedBg(theme.surfaceContainer, UiTokens.CORNER_SM)
        }
        form.addView(lastResultView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ===== 底部按钮 =====
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
        val testBtn = Button(this).apply {
            text = getString(R.string.dm_test)
            setTextColor(Color.WHITE)
            isAllCaps = true
            setOnClickListener { testOnScreen() }
        }
        testRow.addView(testBtn, weighted(testBtn))
        SegmentStyle.applyRow(testRow, listOf(null), theme.outline, theme.onSurface)
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
        SegmentStyle.applyRow(row2, listOf(null, null), theme.outline, theme.onSurface)
        bottomBar.addView(row2)
        root.addView(bottomBar)

        setContentView(root)
        updateCanvasSummary()
        refreshPreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::contentScene.isInitialized) {
            outState.putString(STATE_SCENE, CanvasSceneCodec.sceneToJsonString(contentScene))
        }
    }

    // ===== helpers: form 构建 =====

    private fun weighted(b: Button): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (4 * d()).toInt()
            marginEnd = (4 * d()).toInt()
        }

    private fun d() = resources.displayMetrics.density

    private fun dialogContentWidthDp(): Float {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val min = (120 * d()).toInt()
        val max = (metrics.widthPixels - (32 * d()).toInt()).coerceAtLeast(min)
        val card = (metrics.widthPixels * ScriptDialogSpec.DEFAULT_WIDTH_PCT / 100).coerceIn(min, max)
        return card / d()
    }

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
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
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

    // ===== 内容画布 =====

    private fun updateCanvasSummary() {
        if (!::canvasSummary.isInitialized) return
        canvasSummary.text = getString(R.string.dm_canvas_summary_fmt, contentScene.flatten().size)
    }

    private fun openCanvasDesigner() {
        val intent = Intent(this, CanvasDesignerActivity::class.java)
            .putExtra(CanvasDesignerActivity.EXTRA_SCENE, CanvasSceneCodec.sceneToJsonString(contentScene))
            .putExtra(CanvasDesignerActivity.EXTRA_HEIGHT, contentScene.height.toInt().coerceIn(64, 1600))
            .putExtra(CanvasDesignerActivity.EXTRA_KEY, "main")
            .putExtra(CanvasDesignerActivity.EXTRA_DIALOG_MODE, true)
            .putExtra(CanvasDesignerActivity.EXTRA_DIALOG_WIDTH, contentWidthDp)
        startActivityForResult(intent, REQ_CANVAS_DESIGNER)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CANVAS_DESIGNER || resultCode != RESULT_OK) return
        val json = data?.getStringExtra(CanvasDesignerActivity.EXTRA_SCENE) ?: return
        contentScene = runCatching { CanvasSceneCodec.parseScene(json) }.getOrNull() ?: return
        updateCanvasSummary()
        refreshPreview()
    }

    // ===== 组装 Request =====

    private fun buildRequest(id: String): ScriptDialogSpec.Request {
        val scene = contentScene.copy(
            width = contentScene.width.takeIf { it > 0f } ?: contentWidthDp
        )
        return DialogWorkshopSpec.buildRequest(
            id = id,
            title = titleEdit.text.toString().trim(),
            scene = scene,
            style = ScriptDialogSpec.Style(
                theme = selectedOption(themeGroup) ?: "dark",
                accent = accent,
                radiusDp = radiusSeek.progress,
                position = selectedOption(positionGroup) ?: "center",
                anim = selectedOption(animGroup) ?: "scale"
            )
        )
    }

    @Suppress("ComplexCondition")
    private fun refreshPreview() {
        val request = buildRequest("preview")
        val root = previewRoot
        val card = previewCard
        val renderer = previewRenderer
        if (root != null && card != null && renderer != null && root.parent === previewHost) {
            // 原位更新：不再 removeAllViews，也不再新建 ScriptDialogRenderer。
            renderer.updateCard(card, request) {}
            return
        }
        previewHost.removeAllViews()
        val newRenderer = ScriptDialogRenderer(this)
        val newRoot = newRenderer.buildRoot(request) {}
        val newCard = newRoot.getChildAt(0) as? LinearLayout
        previewRenderer = newRenderer
        previewRoot = newRoot
        previewCard = newCard
        previewHost.addView(
            newRoot,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
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
        if (::lastResultView.isInitialized) {
            lastResultView.text = getString(R.string.dm_debug_empty)
        }
        val existing = testOverlay
        if (existing != null && existing.isAlive()) {
            // 重复点运行：同一个窗口原位换内容，不叠第二个浮窗。
            existing.updateContent(buildRequest("test")) { result ->
                showTestResult(result)
            }
            return
        }
        val overlay = ScriptDialogOverlay(applicationContext, buildRequest("test")) { result ->
            showTestResult(result)
        }
        testOverlay = overlay
        overlay.onDismissed = {
            if (testOverlay === overlay) testOverlay = null
        }
        overlay.show()
    }

    override fun onDestroy() {
        testOverlay?.dismiss()
        testOverlay = null
        previewRenderer = null
        previewRoot = null
        previewCard = null
        super.onDestroy()
    }

    private fun showTestResult(result: ScriptDialogSpec.Result) {
        if (!::lastResultView.isInitialized) return
        lastResultView.text = ScriptDialogSpec.resultToJsonString(result)
    }

    private fun exportCommand() {
        val request = buildRequest("x")
        val canvas = request.ui.rows.filterIsInstance<ScriptDialogSpec.Row.Canvas>().firstOrNull() ?: return
        val parts = mutableListOf<String>()
        if (request.ui.title.isNotEmpty()) parts.add("--title ${shellQuote(request.ui.title)}")
        parts.add("--theme ${request.style.theme}")
        request.style.accent?.let { parts.add("--accent ${shellQuote(colorHex(it))}") }
        parts.add("--radius ${request.style.radiusDp}")
        parts.add("--position ${request.style.position}")
        parts.add("--anim ${request.style.anim}")
        parts.add("--canvas ${shellQuote(CanvasSceneCodec.sceneToJsonString(canvas.row.scene))}")
        parts.add("--canvas-height ${canvas.row.heightDp}")
        if (canvas.row.key.isNotEmpty()) parts.add("--canvas-key ${shellQuote(canvas.row.key)}")
        val sb = StringBuilder("termlou-ui")
        for (p in parts) sb.append(" \\\n  ").append(p)
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("termlou-ui", sb.toString()))
        Snackbar.make(root, getString(R.string.dm_copied), Snackbar.LENGTH_SHORT).show()
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun colorHex(c: Int): String = "#%06X".format(c and 0x00FFFFFF)
}
