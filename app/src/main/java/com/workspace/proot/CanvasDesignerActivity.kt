package com.workspace.proot

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas 图形化设计器。
 *
 * 与运行时共用 [CanvasHostView] / [CanvasSceneCodec]，所以设计态所见即运行态。
 * 所有编辑只改内存场景并 invalidate 画布；属性面板/图层/动画只在编辑结束时重建，
 * 不重建浮窗，也不写任何请求/结果文件。
 */
class CanvasDesignerActivity : Activity() {

    companion object {
        const val EXTRA_SCENE = "canvas_scene"
        const val EXTRA_HEIGHT = "canvas_height"
        const val EXTRA_KEY = "canvas_key"
        const val EXTRA_DIALOG_MODE = "dialog_mode"
        const val EXTRA_DIALOG_WIDTH = "dialog_width"
        private const val REQ_IMAGE = 4301
        private const val MAX_UNDO = 80
        private const val BUTTON_BAR_TYPE = "__button_bar__"
    }

    /** 撤销栈要连同行高和更新标识一起恢复，否则「即时生效」的设置无法撤销。 */
    private data class EditorState(
        val scene: CanvasScene,
        val rowHeight: Int,
        val key: String
    )

    private val theme: ThemeColors by lazy {
        PaletteStore.theme(getSharedPreferences("term-lou-settings", MODE_PRIVATE))
    }

    private lateinit var root: LinearLayout
    private lateinit var host: CanvasHostView
    private lateinit var layersBox: LinearLayout
    private lateinit var propsBox: LinearLayout
    private lateinit var timelineBox: LinearLayout
    private lateinit var animPanelBox: LinearLayout
    private lateinit var panelHost: LinearLayout
    private lateinit var panelScroll: ScrollView
    private lateinit var tabLayers: Button
    private lateinit var tabProps: Button
    private lateinit var tabAnim: Button
    private lateinit var emptyState: View
    private lateinit var statusText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var playBtn: Button
    private lateinit var replayBtn: Button
    private lateinit var loopCheck: CheckBox
    private lateinit var undoBtn: Button
    private lateinit var redoBtn: Button

    private enum class PanelTab { LAYERS, PROPS, ANIM }

    private var currentTab = PanelTab.PROPS
    private var showAppearance = false
    private var showCanvasAdvanced = false
    private var loopPreview = true
    private var rowHeightDp = 220
    private var canvasKey = ""
    private var dialogMode = false
    private var dialogWidthDp = 0f
    private var initialSnapshot = ""
    private var resumePlaybackOnResume = false

    private val undoStack = ArrayDeque<EditorState>()
    private val redoStack = ArrayDeque<EditorState>()
    private var pendingBeforeEdit: EditorState? = null
    private var suppressFieldCallbacks = false
    private var focusedFieldCommit: (() -> Unit)? = null
    private var seekDragging = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!::host.isInitialized) return
            if (!hasAnimations()) {
                if (host.isPlaying()) host.pause()
                refreshTimeUi()
                return
            }
            if (host.isPlaying() && !hasInfiniteAnimations()) {
                val end = finiteEndMs()
                if (end > 0 && host.currentTimeMs() >= end) {
                    if (loopPreview) {
                        host.seek(0)
                    } else {
                        host.pause()
                        host.seek(end)
                    }
                }
            }
            refreshTimeUi()
            if (host.isPlaying()) mainHandler.postDelayed(this, 80)
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initial = savedInstanceState?.getString(EXTRA_SCENE)
            ?: intent.getStringExtra(EXTRA_SCENE)
        val scene = initial?.let { runCatching { CanvasSceneCodec.parseScene(it) }.getOrNull() }
            ?: CanvasScene(width = 360f, height = 240f, background = "#121212")
        rowHeightDp = (savedInstanceState?.getInt(EXTRA_HEIGHT, -1)
            ?.takeIf { it > 0 }
            ?: intent.getIntExtra(EXTRA_HEIGHT, 220)).coerceIn(64, 1600)
        canvasKey = savedInstanceState?.getString(EXTRA_KEY)
            ?: intent.getStringExtra(EXTRA_KEY).orEmpty()
        dialogMode = savedInstanceState?.getBoolean(EXTRA_DIALOG_MODE, false)
            ?: intent.getBooleanExtra(EXTRA_DIALOG_MODE, false)
        dialogWidthDp = savedInstanceState?.getFloat(EXTRA_DIALOG_WIDTH, 0f)
            ?.takeIf { it > 0f }
            ?: intent.getFloatExtra(EXTRA_DIALOG_WIDTH, 0f)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }

        root.addView(buildTopBar())
        root.addView(buildCanvas())
        root.addView(buildActionBar())
        root.addView(buildPanels(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.1f
        ))

        setContentView(root)
        root.isFocusableInTouchMode = true
        root.requestFocus()
        host.setScene(scene, resetClock = true)
        host.pause()
        host.seek(0)
        host.select(null)
        initialSnapshot = editorSignature()
        refreshPanels()
        updateUndoRedo()
    }

    override fun onResume() {
        super.onResume()
        startTicker()
        if (resumePlaybackOnResume && hasAnimations()) host.play()
        resumePlaybackOnResume = false
    }

    override fun onPause() {
        mainHandler.removeCallbacks(ticker)
        flushFocusedField()
        resumePlaybackOnResume = ::host.isInitialized && host.isPlaying()
        if (::host.isInitialized) host.pause()
        pendingBeforeEdit = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::host.isInitialized) {
            flushFocusedField()
            outState.putString(EXTRA_SCENE, host.snapshotJson())
            outState.putInt(EXTRA_HEIGHT, rowHeightDp)
            outState.putString(EXTRA_KEY, canvasKey)
            outState.putBoolean(EXTRA_DIALOG_MODE, dialogMode)
            outState.putFloat(EXTRA_DIALOG_WIDTH, dialogWidthDp)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }

    @Suppress("DEPRECATION")
    private fun handleBack() {
        if (::host.isInitialized) flushFocusedField()
        if (!::host.isInitialized || editorSignature() == initialSnapshot) {
            super.onBackPressed()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.canvas_back_title))
            .setMessage(getString(R.string.canvas_back_message))
            .setPositiveButton(getString(R.string.canvas_back_save)) { _, _ -> finishWithScene() }
            .setNeutralButton(getString(R.string.canvas_back_discard)) { _, _ -> finish() }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    // ================= 顶层 UI =================

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.surfaceVariant)
            setPaddingMd(8, 4, 8, 4)
        }
        bar.addView(squareButton("←", getString(R.string.canvas_back), sizeDp = 40, addGap = false) {
            handleBack()
        })
        bar.addView(TextView(this).apply {
            text = getString(R.string.canvas_designer_title)
            setTextColor(theme.onSurface)
            textMd(UiTokens.TEXT_TITLE, UiTokens.LINE_TITLE, bold = true)
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minimumWidth = 0
            gravity = Gravity.CENTER_VERTICAL
            setPaddingMd(6, 0, 6, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        undoBtn = squareButton("↶", getString(R.string.canvas_undo)) { undo() }
        redoBtn = squareButton("↷", getString(R.string.canvas_redo)) { redo() }
        bar.addView(undoBtn)
        bar.addView(redoBtn)
        bar.addView(squareButton("⋯", getString(R.string.canvas_more)) { showMoreMenu() })
        bar.addView(squareButton(
            getString(R.string.canvas_done), getString(R.string.canvas_done), primary = true
        ) { finishWithScene() })
        return bar
    }

    /** 顶栏/列表用的固定方形按钮，避免 Button 默认最小宽度把一行撑破。 */
    @Suppress("LongParameterList")
    private fun squareButton(
        symbol: String,
        description: String,
        sizeDp: Int = 44,
        primary: Boolean = false,
        addGap: Boolean = true,
        action: () -> Unit
    ): Button {
        val b = Button(this).apply {
            text = symbol
            contentDescription = description
            setTextColor(if (primary) Color.WHITE else theme.onSurface)
            textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPaddingMd(0, 0, 0, 0)
            minimumWidth = 0
            minimumHeight = 0
            isAllCaps = false
            if (primary) ButtonStyle.apply(this, theme.primary, theme.primaryVisible)
            else ButtonStyle.outlined(this, theme.outline)
            setOnClickListener { action() }
        }
        b.layoutParams = LinearLayout.LayoutParams(dpPx(sizeDp), dpPx(sizeDp)).apply {
            if (addGap) marginStart = dpPx(4)
        }
        return b
    }

    private fun buildCanvas(): View {
        host = CanvasHostView(this, CanvasScene())
        host.designMode = true
        host.showGrid = true
        host.snapToGrid = true
        host.onSelectionChanged = { id ->
            if (id != null) currentTab = PanelTab.PROPS
            refreshPanels()
        }
        host.onEditStart = { pendingBeforeEdit = editorState() }
        host.onSceneChanged = { s ->
            val before = pendingBeforeEdit
            pendingBeforeEdit = null
            if (before != null && before.scene != s) {
                pushEditorState(before)
            }
            refreshPanels()
        }

        val wrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.9f
            )
            roundedBg(0xFF101010.toInt(), UiTokens.CORNER_MD, 1f, theme.outline)
            setPaddingMd(1, 1, 1, 1)
        }
        wrap.addView(host, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        emptyState = buildEmptyState()
        wrap.addView(emptyState, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        return wrap
    }

    private fun buildEmptyState(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumWidth = dpPx(240)
            setPaddingMd(18, 16, 18, 16)
            roundedBg(theme.surfaceContainer, UiTokens.CORNER_MD, 1f, theme.outline)
            visibility = View.GONE
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.canvas_empty_title)
            setTextColor(theme.onSurface)
            textMd(UiTokens.TEXT_TITLE, UiTokens.LINE_TITLE, bold = true)
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.canvas_empty_hint)
            setTextColor(theme.onSurfaceVariant)
            textCompact()
            gravity = Gravity.CENTER
            setPaddingMd(0, 4, 0, 10)
        })
        fun row(vararg buttons: Button) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            buttons.forEachIndexed { index, button ->
                r.addView(button, LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    if (index < buttons.lastIndex) marginEnd = dpPx(6)
                })
            }
            box.addView(r, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        row(
            miniButton(getString(R.string.canvas_empty_text)) { addNode(CanvasSpec.TYPE_TEXT) },
            miniButton(getString(R.string.canvas_empty_progress)) { addNode(CanvasSpec.TYPE_PROGRESS) }
        )
        row(
            miniButton(getString(R.string.canvas_empty_demo)) { replaceWithDemo() },
            miniButton(getString(R.string.canvas_empty_import)) { showJsonDialog() }
        )
        return box
    }

    private fun updateEmptyState() {
        if (!::emptyState.isInitialized || !::host.isInitialized) return
        emptyState.visibility = if (host.scene.nodes.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 画布下方只保留一条操作带：添加、状态摘要、画布设置。 */
    private fun buildActionBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingMd(8, 4, 8, 4)
        }
        row.addView(primaryButton(getString(R.string.canvas_add_node)) { showAddMenu() })
        statusText = TextView(this).apply {
            setTextColor(theme.onSurfaceVariant)
            textMeta()
            isSingleLine = true
            maxLines = 1
            minimumWidth = 0
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPaddingMd(4, 0, 6, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(statusText)
        row.addView(miniButton(getString(R.string.canvas_settings)) { openCanvasProps() })
        return row
    }

    private fun openCanvasProps() {
        flushFocusedField()
        host.select(null)
        currentTab = PanelTab.PROPS
        updateTabStyles()
        refreshPanels()
    }

    private fun buildPanels(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingMd(8, 0, 8, 4)
        }
        tabLayers = tabButton(getString(R.string.canvas_layers))
        tabProps = tabButton(getString(R.string.canvas_props))
        tabAnim = tabButton(getString(R.string.canvas_timeline))
        tabLayers.setOnClickListener { selectTab(PanelTab.LAYERS) }
        tabProps.setOnClickListener { selectTab(PanelTab.PROPS) }
        tabAnim.setOnClickListener { selectTab(PanelTab.ANIM) }
        tabRow.addView(tabLayers)
        tabRow.addView(tabProps)
        tabRow.addView(tabAnim)
        column.addView(tabRow)

        panelHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panelScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(panelHost)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        column.addView(panelScroll)

        layersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        propsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        timelineBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        animPanelBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buildAnimHeader())
            addView(buildPlaybackCard())
            addView(timelineBox)
        }
        currentTab = PanelTab.PROPS
        updateTabStyles()
        return column
    }

    private fun buildAnimHeader(): View {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(sectionHeader(getString(R.string.canvas_timeline)).apply {
            isSingleLine = true
            maxLines = 1
            minimumWidth = 0
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        head.addView(miniButton(getString(R.string.canvas_add_anim)) { chooseAnimationPreset() })
        return head
    }

    private fun buildPlaybackCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(8, 6, 8, 4)
            roundedBg(theme.surfaceContainer, UiTokens.CORNER_SM)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpPx(6) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playBtn = miniButton(getString(R.string.canvas_play)) { togglePlay() }
        replayBtn = miniButton(getString(R.string.canvas_replay)) { replayAnimations() }
        playBtn.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = dpPx(4) }
        replayBtn.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = dpPx(4) }
        row.addView(playBtn)
        row.addView(replayBtn)
        timeLabel = TextView(this).apply {
            setTextColor(theme.onSurfaceVariant)
            textMeta()
            isSingleLine = true
            maxLines = 1
            minimumWidth = 0
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
        }
        row.addView(timeLabel)
        card.addView(row)

        seekBar = SeekBar(this).apply {
            max = 1_000
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        host.seek(p.toLong())
                        refreshTimeUi()
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    seekDragging = true
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    seekDragging = false
                }
            })
        }
        card.addView(seekBar)

        loopCheck = CheckBox(this).apply {
            text = getString(R.string.canvas_anim_loop_preview)
            isChecked = loopPreview
            setTextColor(theme.onSurfaceVariant)
            textMeta()
            setPaddingMd(0, 2, 0, 2)
            setOnCheckedChangeListener { _, b ->
                loopPreview = b
                refreshTimeUi()
            }
        }
        card.addView(loopCheck)
        return card
    }

    private fun tabButton(label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
        isSingleLine = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPaddingMd(8, 4, 8, 4)
        minimumWidth = 0
        minimumHeight = dpPx(38)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dpPx(4)
        }
    }

    private fun updateTabStyles() {
        if (!::tabLayers.isInitialized) return
        fun style(b: Button, selected: Boolean) {
            if (selected) {
                ButtonStyle.apply(b, theme.primary, theme.primaryVisible)
                b.setTextColor(Color.WHITE)
            } else {
                ButtonStyle.outlined(b, theme.outline)
                b.setTextColor(theme.onSurface)
            }
        }
        style(tabLayers, currentTab == PanelTab.LAYERS)
        style(tabProps, currentTab == PanelTab.PROPS)
        style(tabAnim, currentTab == PanelTab.ANIM)
    }

    private fun selectTab(tab: PanelTab) {
        flushFocusedField()
        currentTab = tab
        updateTabStyles()
        refreshPanels()
    }

    private fun renderPanel() {
        if (!::panelHost.isInitialized) return
        panelHost.removeAllViews()
        panelHost.setPaddingMd(12, 0, 12, 24)
        when (currentTab) {
            PanelTab.LAYERS -> panelHost.addView(layersBox)
            PanelTab.PROPS -> panelHost.addView(propsBox)
            PanelTab.ANIM -> panelHost.addView(animPanelBox)
        }
    }

    private fun showAddMenu() {
        flushFocusedField()
        val types = listOf(
            CanvasSpec.TYPE_TEXT to R.string.canvas_tool_text,
            CanvasSpec.TYPE_INPUT to R.string.canvas_tool_input,
            CanvasSpec.TYPE_SELECT to R.string.canvas_tool_select,
            CanvasSpec.TYPE_TOGGLE to R.string.canvas_tool_toggle,
            CanvasSpec.TYPE_BUTTON to R.string.canvas_tool_button,
            "__button_bar__" to R.string.canvas_tool_button_bar,
            CanvasSpec.TYPE_RECT to R.string.canvas_tool_rect,
            CanvasSpec.TYPE_ROUND_RECT to R.string.canvas_tool_round,
            CanvasSpec.TYPE_CIRCLE to R.string.canvas_tool_circle,
            CanvasSpec.TYPE_ELLIPSE to R.string.canvas_tool_ellipse,
            CanvasSpec.TYPE_LINE to R.string.canvas_tool_line,
            CanvasSpec.TYPE_PATH to R.string.canvas_tool_path,
            CanvasSpec.TYPE_PROGRESS to R.string.canvas_tool_progress,
            CanvasSpec.TYPE_IMAGE to R.string.canvas_tool_image
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.canvas_add_node_title))
            .setItems(types.map { getString(it.second) }.toTypedArray()) { _, which ->
                val type = types[which].first
                if (type == BUTTON_BAR_TYPE) showButtonBarDialog() else addNode(type)
            }
            .showStyled(theme)
    }

    private fun showButtonBarDialog() {
        val edit = EditText(this).apply {
            setText(getString(R.string.dm_btns_default))
            setTextColor(theme.onSurface)
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_COMPACT)
            setPaddingMd(10, 6, 10, 6)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(20, 4, 20, 4)
            addView(TextView(this@CanvasDesignerActivity).apply {
                text = getString(R.string.dm_f_btns)
                setTextColor(theme.onSurfaceVariant)
                textMeta()
                setPaddingMd(0, 0, 0, 4)
            })
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.canvas_tool_button_bar))
            .setView(box)
            .setPositiveButton(getString(R.string.sc_confirm)) { _, _ ->
                val buttons = edit.text.toString().split(",").mapNotNull { raw ->
                    val parts = raw.trim().split("=")
                    if (parts.isEmpty() || parts[0].isBlank()) null
                    else ScriptDialogSpec.Button(
                        parts[0].trim(),
                        parts.getOrNull(1)?.trim()?.ifEmpty { parts[0].trim() } ?: parts[0].trim(),
                        parts.getOrNull(2)?.trim()?.ifEmpty { ScriptDialogSpec.BTN_NORMAL } ?: ScriptDialogSpec.BTN_NORMAL
                    )
                }
                addButtonBar(buttons)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    private fun addButtonBar(buttons: List<ScriptDialogSpec.Button>) {
        if (buttons.isEmpty()) return
        val s = scene()
        if (s.flatten().size + buttons.size > CanvasSpec.MAX_NODES) {
            toast(getString(R.string.canvas_node_limit, CanvasSpec.MAX_NODES))
            return
        }
        val rects = CanvasWidgetPlacement.buttonBar(s, buttons.size)
        val used = s.flatten().map { it.id }.toHashSet()
        fun nextId(): String {
            var i = 1
            while (used.contains("n$i")) i++
            return "n$i".also { used.add(it) }
        }
        val nodes = buttons.mapIndexed { index, button ->
            val rect = rects[index]
            val fill = when (button.kind) {
                ScriptDialogSpec.BTN_PRIMARY -> theme.primary
                ScriptDialogSpec.BTN_DANGER -> theme.error
                else -> theme.primaryContainer
            }
            val textColor = if (button.kind == ScriptDialogSpec.BTN_NORMAL) theme.onSurface else Color.WHITE
            CanvasNode(
                id = nextId(),
                type = CanvasSpec.TYPE_BUTTON,
                x = rect.x,
                y = rect.y,
                w = rect.w,
                h = rect.h,
                text = button.text,
                textColor = colorHex(textColor),
                fill = colorHex(fill),
                radius = CanvasWidgetLayout.FIELD_RADIUS,
                textSize = 13f,
                bold = true,
                action = "submit:${button.id}" + if (button.close) "" else ":keep",
                buttonKind = button.kind,
                name = "button ${button.id}"
            )
        }
        pushUndo(s)
        var next = s
        for (node in nodes) next = next.addNode(node)
        val bottom = nodes.maxOf { it.y + it.h } + 24f
        if (bottom > next.height) next = next.copy(height = bottom)
        currentTab = PanelTab.PROPS
        host.setScene(next)
        host.select(nodes.last().id)
        refreshPanels()
    }

    private fun showMoreMenu() {
        flushFocusedField()
        val items = listOf(
            getString(
                if (host.showGrid) R.string.canvas_grid_on else R.string.canvas_grid_off
            ),
            getString(
                if (host.snapToGrid) R.string.canvas_snap_on else R.string.canvas_snap_off
            ),
            getString(R.string.canvas_json),
            getString(R.string.canvas_export_cmd),
            getString(R.string.canvas_help)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.canvas_designer_title))
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        host.showGrid = !host.showGrid
                        host.invalidate()
                        toast(getString(
                            if (host.showGrid) R.string.canvas_grid_on else R.string.canvas_grid_off
                        ))
                    }
                    1 -> {
                        host.snapToGrid = !host.snapToGrid
                        toast(getString(
                            if (host.snapToGrid) R.string.canvas_snap_on else R.string.canvas_snap_off
                        ))
                    }
                    2 -> showJsonDialog()
                    3 -> exportCommand()
                    4 -> showHelpDialog()
                }
            }
            .showStyled(theme)
    }

    // ================= 通用控件 =================

    private fun dpPx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun miniButton(label: String, action: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            setTextColor(theme.onSurface)
            textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPaddingMd(10, 4, 10, 4)
            minimumWidth = 0
            minimumHeight = dpPx(36)
            isAllCaps = false
            ButtonStyle.outlined(this, theme.outline)
            setOnClickListener { action() }
        }
        b.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dpPx(6) }
        return b
    }

    private fun primaryButton(label: String, action: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPaddingMd(12, 4, 12, 4)
            minimumWidth = 0
            minimumHeight = dpPx(36)
            isAllCaps = false
            ButtonStyle.apply(this, theme.primary, theme.primaryVisible)
            setOnClickListener { action() }
        }
        b.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return b
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(theme.onSurface)
        textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY, bold = true)
        setPaddingMd(0, 12, 0, 6)
    }

    private fun hintView(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(theme.onSurfaceVariant)
        textCompact()
        setPaddingMd(0, 4, 0, 8)
    }

    private fun toast(msg: String) {
        Snackbar.make(root, msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun formatNumber(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(Locale.US, "%.2f", v)

    private fun formatTime(ms: Long): String =
        String.format(Locale.US, "%.1fs", ms.coerceAtLeast(0) / 1000f)

    private fun fieldCell(
        label: String,
        value: String,
        multiline: Boolean,
        numeric: Boolean,
        onCommit: (String) -> Unit
    ): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(0, 2, 0, 4)
        }
        wrap.addView(TextView(this).apply {
            text = label
            setTextColor(theme.onSurfaceVariant)
            textMeta()
        })
        val edit = EditText(this).apply {
            setText(value)
            setTextColor(theme.onSurface)
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_COMPACT)
            setPaddingMd(10, 6, 10, 6)
            isSingleLine = !multiline
            isFocusableInTouchMode = true
            isCursorVisible = true
            showSoftInputOnFocus = true
            if (multiline) {
                minLines = 2
                gravity = Gravity.TOP
            } else {
                imeOptions = EditorInfo.IME_ACTION_DONE
            }
            if (numeric) {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
            }
        }
        var committed = value
        fun commit() {
            if (suppressFieldCallbacks) return
            val raw = edit.text.toString()
            if (raw == committed) return
            committed = raw
            onCommit(raw)
        }
        val commitField: () -> Unit = { commit() }
        edit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                focusedFieldCommit = commitField
                scrollFieldIntoView(edit)
            } else {
                commit()
                if (focusedFieldCommit === commitField) focusedFieldCommit = null
            }
        }
        edit.setOnClickListener {
            if (!edit.hasFocus()) edit.requestFocus()
            edit.post {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                edit.clearFocus()
                true
            } else false
        }
        wrap.addView(edit)
        return wrap
    }

    /** 键盘弹出后把当前编辑框滚到可视区，避免只剩标签可见。 */
    private fun scrollFieldIntoView(edit: EditText) {
        edit.postDelayed({
            if (!edit.hasFocus() || !::panelScroll.isInitialized || !::panelHost.isInitialized) {
                return@postDelayed
            }
            val rect = Rect(0, 0, edit.width, edit.height)
            panelHost.offsetDescendantRectToMyCoords(edit, rect)
            panelScroll.smoothScrollTo(0, (rect.top - dpPx(10)).coerceAtLeast(0))
        }, 180)
    }

    private fun flushFocusedField() {
        val commit = focusedFieldCommit ?: return
        focusedFieldCommit = null
        commit()
    }

    private fun propField(
        label: String,
        value: String,
        multiline: Boolean,
        numeric: Boolean,
        onCommit: (String) -> Unit
    ) {
        propsBox.addView(fieldCell(label, value, multiline, numeric, onCommit))
    }

    @Suppress("LongParameterList")
    private fun propFieldPair(
        labelA: String,
        valueA: String,
        onCommitA: (String) -> Unit,
        labelB: String,
        valueB: String,
        onCommitB: (String) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        row.addView(
            fieldCell(labelA, valueA, false, true, onCommitA),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpPx(4)
            }
        )
        row.addView(
            fieldCell(labelB, valueB, false, true, onCommitB),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpPx(4)
            }
        )
        propsBox.addView(row)
    }

    private fun colorField(label: String, value: String, onCommit: (String) -> Unit) {
        propField(label, value, false, false) { raw ->
            val v = raw.trim()
            if (v.isEmpty() || ScriptDialogSpec.parseColor(v) != null) {
                onCommit(v)
            } else {
                toast(getString(R.string.canvas_invalid_color, v))
            }
        }
    }

    private fun boolField(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val cb = CheckBox(this).apply {
            text = label
            isChecked = checked
            setTextColor(theme.onSurface)
            textCompact()
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
                intArrayOf(theme.primaryVisible, theme.onSurfaceVariant)
            )
            setPaddingMd(0, 4, 0, 4)
            setOnCheckedChangeListener { _, b -> if (!suppressFieldCallbacks) onChange(b) }
        }
        propsBox.addView(cb)
    }

    private fun parseNumber(raw: String): Float? {
        val v = raw.trim().toFloatOrNull()
        if (v == null || v.isNaN() || v.isInfinite()) {
            toast(getString(R.string.canvas_invalid_number, raw))
            return null
        }
        return v
    }

    // ================= 场景编辑 =================

    private fun demoScene(): CanvasScene = CanvasScene(
        width = 360f,
        height = 240f,
        background = "#121212",
        nodes = listOf(
            CanvasNode(
                id = "title",
                type = CanvasSpec.TYPE_TEXT,
                x = 24f, y = 28f, w = 300f, h = 32f,
                text = getString(R.string.canvas_demo_title),
                textSize = UiTokens.TEXT_TITLE,
                textColor = "#E6E6E6",
                bold = true,
                name = getString(R.string.canvas_tool_text)
            ),
            CanvasNode(
                id = "bar",
                type = CanvasSpec.TYPE_PROGRESS,
                x = 24f, y = 92f, w = 312f, h = 18f,
                progress = 0.35f,
                radius = 9f,
                trackColor = "#33FFFFFF",
                barColor = "#2D7D46",
                gradientFrom = "#2D7D46",
                gradientTo = "#4DD0E1",
                showLabel = true,
                labelColor = "#E6E6E6",
                labelSize = UiTokens.TEXT_META,
                name = getString(R.string.canvas_tool_progress)
            ),
            CanvasNode(
                id = "tip",
                type = CanvasSpec.TYPE_TEXT,
                x = 24f, y = 132f, w = 312f, h = 52f,
                text = getString(R.string.canvas_demo_hint),
                textSize = UiTokens.TEXT_COMPACT,
                textColor = "#9E9E9E",
                name = getString(R.string.canvas_tool_text)
            )
        ),
        animations = listOf(
            CanvasAnimation(
                id = "demo-progress",
                target = "bar",
                property = "progress",
                from = 0f,
                to = 1f,
                durationMs = 1800,
                repeat = -1,
                easing = "ease-in-out"
            )
        )
    )

    private fun replaceWithDemo() {
        flushFocusedField()
        pushUndo(host.scene)
        pendingBeforeEdit = null
        host.setScene(demoScene(), resetClock = true)
        host.select(null)
        host.play()
        currentTab = PanelTab.ANIM
        refreshPanels()
        startTicker()
    }

    private fun nextNodeId(prefix: String): String {
        val ids = scene().flatten().map { it.id }.toHashSet()
        var i = 1
        while (ids.contains("$prefix$i")) i++
        return "$prefix$i"
    }

    private fun nextWidgetKey(prefix: String): String {
        val keys = scene().flatten().map { it.key }.filter { it.isNotEmpty() }.toHashSet()
        var i = 1
        while (keys.contains("$prefix$i")) i++
        return "$prefix$i"
    }

    private fun scene(): CanvasScene = host.scene

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun addNode(type: String) {
        val s = scene()
        if (s.flatten().size >= CanvasSpec.MAX_NODES) {
            toast(getString(R.string.canvas_node_limit, CanvasSpec.MAX_NODES))
            return
        }
        val seq = s.nodes.size
        val offset = (seq % 6) * 8f
        val typeName = typeLabel(type)
        val id = nextNodeId("n")
        val inputRect = CanvasWidgetPlacement.input(s)
        val selectRect = CanvasWidgetPlacement.select(s, getString(R.string.canvas_sample_label), 3)
        val toggleRect = CanvasWidgetPlacement.toggle(s)
        val textRect = CanvasWidgetPlacement.text(s)
        val buttonRect = CanvasWidgetPlacement.button(s)
        val node = when (type) {
            CanvasSpec.TYPE_INPUT -> CanvasNode(
                id, type, x = inputRect.x, y = inputRect.y, w = inputRect.w, h = inputRect.h,
                key = nextWidgetKey("input"), label = getString(R.string.canvas_sample_label),
                text = "", textSize = CanvasWidgetLayout.FIELD_TEXT_SIZE,
                labelSize = CanvasWidgetLayout.LABEL_SIZE, labelColor = "#9A9A9A",
                textColor = "#E6E6E6", fill = "#2A2A2A", radius = CanvasWidgetLayout.FIELD_RADIUS,
                name = "$typeName ${seq + 1}"
            )
            CanvasSpec.TYPE_SELECT -> CanvasNode(
                id, type, x = selectRect.x, y = selectRect.y, w = selectRect.w, h = selectRect.h,
                key = nextWidgetKey("select"), label = getString(R.string.canvas_sample_label),
                options = listOf("A", "B", "C"),
                textSize = CanvasWidgetLayout.OPTION_SIZE, labelSize = CanvasWidgetLayout.LABEL_SIZE,
                labelColor = "#9A9A9A", textColor = "#E6E6E6", barColor = "#2D7D46",
                fill = "#2A2A2A", radius = CanvasWidgetLayout.FIELD_RADIUS,
                name = "$typeName ${seq + 1}"
            )
            CanvasSpec.TYPE_TOGGLE -> CanvasNode(
                id, type, x = toggleRect.x, y = toggleRect.y, w = toggleRect.w, h = toggleRect.h,
                key = nextWidgetKey("toggle"), label = getString(R.string.canvas_sample_label),
                textSize = CanvasWidgetLayout.LABEL_SIZE, labelSize = CanvasWidgetLayout.LABEL_SIZE,
                labelColor = "#9A9A9A", textColor = "#9A9A9A", barColor = "#2D7D46",
                fill = "#2A2A2A", radius = CanvasWidgetLayout.FIELD_RADIUS,
                name = "$typeName ${seq + 1}"
            )
            CanvasSpec.TYPE_BUTTON -> CanvasNode(
                id, type, x = buttonRect.x, y = buttonRect.y, w = buttonRect.w, h = buttonRect.h,
                text = getString(R.string.canvas_sample_button), action = "submit:ok",
                buttonKind = ScriptDialogSpec.BTN_PRIMARY, textSize = 13f, textColor = "#FFFFFF",
                fill = "#2D7D46", radius = CanvasWidgetLayout.FIELD_RADIUS, bold = true,
                name = "$typeName ${seq + 1}"
            )
            CanvasSpec.TYPE_ROUND_RECT -> CanvasNode(
                id, type, x = 36f + offset, y = 36f + offset, w = 132f, h = 80f,
                fill = "#2D7D46", radius = 12f, name = "$typeName ${seq + 1}"
            )
            CanvasSpec.TYPE_CIRCLE -> CanvasNode(
                id, type, x = 48f + offset, y = 48f + offset, w = 88f, h = 88f,
                fill = "#0E639C", name = "$typeName ${seq + 1}"
            )
            CanvasSpec.TYPE_ELLIPSE -> CanvasNode(
                id, type, x = 48f + offset, y = 48f + offset, w = 120f, h = 72f,
                fill = "#0E639C", name = "$typeName ${seq + 1}"
            )
            CanvasSpec.TYPE_TEXT -> CanvasNode(
                id, type, x = textRect.x, y = textRect.y, w = textRect.w, h = textRect.h,
                text = getString(R.string.canvas_sample_text), textSize = UiTokens.TEXT_META,
                textColor = "#E6E6E6", wrap = true, name = "$typeName ${seq + 1}"
            )
            CanvasSpec.TYPE_LINE -> CanvasNode(
                id, type, x = 40f + offset, y = 96f + offset, w = 240f, h = 0f,
                points = listOf(CanvasPoint(0f, 0f), CanvasPoint(240f, 0f)),
                stroke = "#4DD0E1", strokeWidth = 3f, name = "$typeName ${seq + 1}"
            )
            CanvasSpec.TYPE_PATH -> CanvasNode(
                id, type, x = 56f + offset, y = 72f + offset, w = 176f, h = 96f,
                points = listOf(
                    CanvasPoint(0f, 96f), CanvasPoint(88f, 0f), CanvasPoint(176f, 96f)
                ),
                closed = true, fill = "#332D7D46", stroke = "#4DD0E1", strokeWidth = 3f,
                name = "$typeName ${seq + 1}"
            )
            CanvasSpec.TYPE_PROGRESS -> CanvasNode(
                id, type, x = 32f + offset, y = 96f + offset,
                w = min(280f, s.width - 64f), h = 18f,
                progress = 0.35f, radius = 9f,
                trackColor = "#33FFFFFF", barColor = "#2D7D46",
                gradientFrom = "#2D7D46", gradientTo = "#4DD0E1",
                showLabel = true, labelColor = "#E6E6E6",
                labelSize = UiTokens.TEXT_META, name = "$typeName ${seq + 1}"
            )
            CanvasSpec.TYPE_IMAGE -> CanvasNode(
                id, type, x = 40f + offset, y = 48f + offset, w = 180f, h = 112f,
                fit = CanvasSpec.FIT_CONTAIN, name = "$typeName ${seq + 1}"
            )
            else -> CanvasNode(
                id, type, x = 36f + offset, y = 36f + offset, w = 148f, h = 88f,
                fill = "#2D7D46", radius = 0f, name = "$typeName ${seq + 1}"
            )
        }
        val expanded = if (node.y + node.h + 16f > s.height) {
            s.copy(height = node.y + node.h + 24f)
        } else {
            s
        }
        pushUndo(s)
        currentTab = PanelTab.PROPS
        host.setScene(expanded.addNode(node))
        host.select(id)
        refreshPanels()
        if (type == CanvasSpec.TYPE_IMAGE) pickImage()
    }

    private fun deleteNode(id: String) {
        val s = scene()
        if (s.findNode(id) == null) return
        pushUndo(s)
        host.setScene(s.removeNode(id))
        if (host.selectedId == id) host.select(null)
        refreshPanels()
    }

    private fun toggleVisible(id: String) {
        val s = scene()
        val n = s.findNode(id) ?: return
        pushUndo(s)
        host.setScene(s.updateNode(id) { it.copy(visible = !n.visible) })
        refreshPanels()
    }

    @Suppress("ReturnCount")
    private fun duplicateNode(id: String) {
        val s = scene()
        val source = s.findNode(id) ?: return
        if (s.flatten().size + subtreeCount(source) > CanvasSpec.MAX_NODES) {
            toast(getString(R.string.canvas_node_limit, CanvasSpec.MAX_NODES))
            return
        }
        val used = s.flatten().map { it.id }.toHashSet()
        val usedKeys = s.flatten().map { it.key }.filter { it.isNotEmpty() }.toHashSet()
        fun copyTree(src: CanvasNode): CanvasNode {
            var i = 1
            while (used.contains("n$i")) i++
            val newId = "n$i"
            used.add(newId)
            val newKey = if (src.type == CanvasSpec.TYPE_INPUT ||
                src.type == CanvasSpec.TYPE_SELECT || src.type == CanvasSpec.TYPE_TOGGLE
            ) {
                val prefix = when (src.type) {
                    CanvasSpec.TYPE_INPUT -> "input"
                    CanvasSpec.TYPE_SELECT -> "select"
                    else -> "toggle"
                }
                var k = 1
                while (usedKeys.contains("$prefix$k")) k++
                "$prefix$k".also { usedKeys.add(it) }
            } else {
                src.key
            }
            return src.copy(
                id = newId,
                key = newKey,
                x = src.x + 8f,
                y = src.y + 8f,
                children = src.children.map { copyTree(it) }
            )
        }
        val clone = copyTree(source)
        val nodes = insertAfter(s.nodes, id, clone) ?: return
        pushUndo(s)
        host.setScene(s.copy(nodes = nodes))
        host.select(clone.id)
        currentTab = PanelTab.PROPS
        refreshPanels()
    }

    private fun subtreeCount(n: CanvasNode): Int = 1 + n.children.sumOf { subtreeCount(it) }

    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    private fun insertAfter(list: List<CanvasNode>, id: String, node: CanvasNode): List<CanvasNode>? {
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) return list.toMutableList().apply { add(idx + 1, node) }
        for ((i, n) in list.withIndex()) {
            if (n.children.isEmpty()) continue
            val child = insertAfter(n.children, id, node) ?: continue
            return list.toMutableList().apply { set(i, n.copy(children = child)) }
        }
        return null
    }

    private fun commitSelected(
        refresh: Boolean = false,
        transform: (CanvasNode) -> CanvasNode
    ) {
        val id = host.selectedId ?: return
        val before = host.scene
        val after = before.updateNode(id, transform)
        if (after == before) return
        pushUndo(before)
        host.setScene(after)
        if (refresh) {
            refreshPanels()
        } else {
            refreshLayers()
            updateStatus()
        }
    }

    private fun updateCanvas(
        refresh: Boolean = false,
        transform: (CanvasScene) -> CanvasScene
    ) {
        val before = host.scene
        val after = transform(before)
        if (after == before) return
        pushUndo(before)
        host.setScene(after)
        if (refresh) refreshPanels() else updateStatus()
    }

    private fun setRowHeight(value: Int) {
        if (value == rowHeightDp) return
        pushUndo(host.scene)
        rowHeightDp = value
    }

    private fun setCanvasKey(value: String) {
        if (value == canvasKey) return
        pushUndo(host.scene)
        canvasKey = value
    }

    // ================= 撤销 / 重做 =================

    private fun editorState(): EditorState = EditorState(host.scene, rowHeightDp, canvasKey)

    private fun editorSignature(): String =
        host.snapshotJson() + "\n" + rowHeightDp.toString() + "\n" + canvasKey

    private fun pushUndo(scene: CanvasScene) {
        pushEditorState(EditorState(scene, rowHeightDp, canvasKey))
    }

    private fun pushEditorState(state: EditorState) {
        if (undoStack.peekLast() != state) {
            undoStack.addLast(state)
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        }
        redoStack.clear()
        updateUndoRedo()
    }

    private fun applyEditorState(state: EditorState) {
        rowHeightDp = state.rowHeight
        canvasKey = state.key
        host.setScene(state.scene, resetClock = true)
        val selected = host.selectedId
        if (selected != null && state.scene.findNode(selected) == null) host.select(null)
        refreshPanels()
        updateUndoRedo()
    }

    private fun undo() {
        flushFocusedField()
        val prev = undoStack.pollLast() ?: return
        redoStack.addLast(editorState())
        applyEditorState(prev)
    }

    private fun redo() {
        flushFocusedField()
        val next = redoStack.pollLast() ?: return
        undoStack.addLast(editorState())
        applyEditorState(next)
    }

    private fun updateUndoRedo() {
        if (!::undoBtn.isInitialized) return
        undoBtn.isEnabled = undoStack.isNotEmpty()
        redoBtn.isEnabled = redoStack.isNotEmpty()
        undoBtn.alpha = if (undoBtn.isEnabled) 1f else 0.4f
        redoBtn.alpha = if (redoBtn.isEnabled) 1f else 0.4f
    }

    // ================= 图层 =================

    private fun refreshLayers() {
        layersBox.removeAllViews()
        val s = scene()
        if (s.nodes.isEmpty()) {
            layersBox.addView(hintView(getString(R.string.canvas_no_layers)))
            return
        }
        layersBox.addView(hintView(getString(R.string.canvas_layers_order_hint)))
        fun walk(list: List<CanvasNode>, depth: Int) {
            // 场景列表从底到顶绘制；图层面板按用户直觉反过来显示。
            for (n in list.asReversed()) {
                layersBox.addView(layerRow(n, depth))
                if (n.children.isNotEmpty()) walk(n.children, depth + 1)
            }
        }
        walk(s.nodes, 0)
    }

    private fun layerRow(n: CanvasNode, depth: Int): View {
        val indent = 8 + depth * 14
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingMd(indent, 6, 8, 6)
            roundedBg(
                if (host.selectedId == n.id) theme.primaryContainer else theme.surfaceContainer,
                UiTokens.CORNER_SM
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpPx(4) }
            setOnClickListener { host.select(n.id) }
        }
        val label = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        label.addView(TextView(this).apply {
            text = (if (depth > 0) "↳ " else "") + n.name.ifBlank { typeLabel(n.type) }
            setTextColor(theme.onSurface)
            textCompact()
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        label.addView(TextView(this).apply {
            text = "${n.id} · ${typeLabel(n.type)}"
            setTextColor(theme.onSurfaceVariant)
            textMeta()
            typeface = Typeface.MONOSPACE
            isSingleLine = true
        })
        row.addView(label)
        row.addView(TextView(this).apply {
            text = "👁"
            textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
            setTextColor(theme.onSurface)
            alpha = if (n.visible) 1f else 0.35f
            gravity = Gravity.CENTER
            contentDescription = getString(
                if (n.visible) R.string.canvas_hide else R.string.canvas_show
            )
            setPaddingMd(10, 6, 10, 6)
            minimumWidth = dpPx(40)
            setOnClickListener { toggleVisible(n.id) }
        })
        return row
    }

    @Suppress("CyclomaticComplexMethod")
    private fun typeLabel(type: String): String = getString(
        when (type) {
            CanvasSpec.TYPE_ROUND_RECT -> R.string.canvas_tool_round
            CanvasSpec.TYPE_CIRCLE -> R.string.canvas_tool_circle
            CanvasSpec.TYPE_ELLIPSE -> R.string.canvas_tool_ellipse
            CanvasSpec.TYPE_TEXT -> R.string.canvas_tool_text
            CanvasSpec.TYPE_LINE -> R.string.canvas_tool_line
            CanvasSpec.TYPE_PATH -> R.string.canvas_tool_path
            CanvasSpec.TYPE_PROGRESS -> R.string.canvas_tool_progress
            CanvasSpec.TYPE_IMAGE -> R.string.canvas_tool_image
            CanvasSpec.TYPE_GROUP -> R.string.canvas_tool_group
            CanvasSpec.TYPE_INPUT -> R.string.canvas_tool_input
            CanvasSpec.TYPE_SELECT -> R.string.canvas_tool_select
            CanvasSpec.TYPE_TOGGLE -> R.string.canvas_tool_toggle
            CanvasSpec.TYPE_BUTTON -> R.string.canvas_tool_button
            else -> R.string.canvas_tool_rect
        }
    )

    // ================= 属性 =================

    private fun refreshProps() {
        flushFocusedField()
        focusedFieldCommit = null
        suppressFieldCallbacks = true
        propsBox.removeAllViews()
        try {
            val n = host.selectedId?.let { scene().findNode(it) }
            if (n == null) {
                buildCanvasProps()
            } else {
                buildNodeProps(n)
            }
        } finally {
            suppressFieldCallbacks = false
        }
    }

    private fun buildCanvasProps() {
        propsBox.addView(sectionHeader(getString(R.string.canvas_props_canvas)))
        propsBox.addView(hintView(getString(R.string.canvas_canvas_hint)))
        val s = scene()
        addCanvasPresets()
        addCanvasGeometryProps(s)
        addCanvasAdvancedProps()
        propsBox.addView(hintView(getString(R.string.canvas_settings_hint)))
    }

    private fun addCanvasPresets() {
        propsBox.addView(hintView(getString(R.string.canvas_preset_hint)))
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingMd(0, 0, 0, 6)
        }
        val presets = listOf(
            getString(R.string.canvas_preset_phone) to (360f to 240f),
            getString(R.string.canvas_preset_square) to (300f to 300f),
            getString(R.string.canvas_preset_wide) to (480f to 270f)
        )
        presets.forEachIndexed { index, (label, size) ->
            presetRow.addView(miniButton(label) {
                updateCanvas(refresh = true) { it.copy(width = size.first, height = size.second) }
            }, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                if (index < presets.lastIndex) marginEnd = dpPx(4)
            })
        }
        propsBox.addView(presetRow)
    }

    private fun addCanvasGeometryProps(s: CanvasScene) {
        if (dialogMode) {
            propsBox.addView(
                hintView(getString(R.string.canvas_settings_width_fixed, formatNumber(dialogWidthDp.takeIf { it > 0f } ?: s.width)))
            )
            propField(
                getString(R.string.canvas_settings_height), formatNumber(s.height), false, true
            ) { raw ->
                val h = parseNumber(raw) ?: return@propField
                if (h !in 32f..4000f) {
                    toast(getString(R.string.canvas_settings_invalid))
                } else {
                    updateCanvas { it.copy(height = h) }
                }
            }
        } else {
            propFieldPair(
                getString(R.string.canvas_settings_width), formatNumber(s.width),
                { raw ->
                    val w = parseNumber(raw) ?: return@propFieldPair
                    if (w !in 32f..4000f) {
                        toast(getString(R.string.canvas_settings_invalid))
                    } else {
                        updateCanvas { it.copy(width = w) }
                    }
                },
                getString(R.string.canvas_settings_height), formatNumber(s.height),
                { raw ->
                    val h = parseNumber(raw) ?: return@propFieldPair
                    if (h !in 32f..4000f) {
                        toast(getString(R.string.canvas_settings_invalid))
                    } else {
                        updateCanvas { it.copy(height = h) }
                    }
                }
            )
            propField(
                getString(R.string.canvas_settings_row_height), rowHeightDp.toString(), false, true
            ) { raw ->
                val rh = parseNumber(raw)?.toInt()
                if (rh == null || rh !in 64..1600) {
                    toast(getString(R.string.canvas_settings_invalid))
                } else {
                    setRowHeight(rh)
                }
            }
        }
        colorField(getString(R.string.canvas_settings_background), s.background) { v ->
            updateCanvas { it.copy(background = v) }
        }
    }

    private fun addCanvasAdvancedProps() {
        propsBox.addView(miniButton(
            getString(
                if (showCanvasAdvanced) R.string.canvas_advanced_less else R.string.canvas_advanced
            )
        ) {
            showCanvasAdvanced = !showCanvasAdvanced
            refreshProps()
        })
        if (!showCanvasAdvanced) return
        propField(getString(R.string.canvas_settings_key), canvasKey, false, false) { raw ->
            val key = raw.trim()
            if (key.isEmpty() || key.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) {
                setCanvasKey(key)
            } else {
                toast(getString(R.string.canvas_settings_invalid))
            }
        }
        propsBox.addView(hintView(getString(R.string.canvas_settings_key_hint)))
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun buildNodeProps(n: CanvasNode) {
        propsBox.addView(sectionHeader(n.name.ifBlank { typeLabel(n.type) }))
        propsBox.addView(TextView(this).apply {
            text = "${n.id} · ${typeLabel(n.type)}"
            setTextColor(theme.onSurfaceVariant)
            textMeta()
            typeface = Typeface.MONOSPACE
            setPaddingMd(0, 0, 0, 6)
        })
        propsBox.addView(actionRow(n))
        propFieldPair(
            getString(R.string.canvas_prop_x), formatNumber(n.x),
            { raw -> parseNumber(raw)?.let { x -> commitSelected { it.withNumber("x", x) } } },
            getString(R.string.canvas_prop_y), formatNumber(n.y),
            { raw -> parseNumber(raw)?.let { y -> commitSelected { it.withNumber("y", y) } } }
        )
        propFieldPair(
            getString(R.string.canvas_prop_w), formatNumber(n.w),
            { raw -> parseNumber(raw)?.let { w -> commitSelected { it.withNumber("w", w) } } },
            getString(R.string.canvas_prop_h), formatNumber(n.h),
            { raw -> parseNumber(raw)?.let { h -> commitSelected { it.withNumber("h", h) } } }
        )

        when (n.type) {
            CanvasSpec.TYPE_TEXT -> textProps(n)
            CanvasSpec.TYPE_IMAGE -> imageProps(n)
            CanvasSpec.TYPE_PROGRESS -> progressProps(n)
            CanvasSpec.TYPE_PATH, CanvasSpec.TYPE_LINE -> pathProps(n)
            CanvasSpec.TYPE_INPUT -> inputProps(n)
            CanvasSpec.TYPE_SELECT -> selectProps(n)
            CanvasSpec.TYPE_TOGGLE -> toggleProps(n)
            CanvasSpec.TYPE_BUTTON -> buttonProps(n)
        }

        propsBox.addView(miniButton(
            getString(if (showAppearance) R.string.canvas_appearance_less else R.string.canvas_appearance)
        ) {
            showAppearance = !showAppearance
            refreshProps()
        })
        if (showAppearance) styleProps(n)

        propsBox.addView(sectionHeader(getString(R.string.canvas_layer_ops)))
        val ops = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ops.addView(
            miniButton(getString(R.string.canvas_layer_duplicate)) { duplicateNode(n.id) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpPx(6)
            }
        )
        ops.addView(
            miniButton(getString(R.string.canvas_layer_delete)) { deleteNode(n.id) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        propsBox.addView(ops)
    }

    /** 点按动作入口 + 一行可读摘要；真正的选择在 [showActionDialog] 里。 */
    private fun actionRow(n: CanvasNode): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingMd(0, 2, 0, 8)
        }
        row.addView(miniButton(getString(R.string.canvas_action_title)) { showActionDialog(n) })
        row.addView(TextView(this).apply {
            text = actionSummary(n.action)
            setTextColor(theme.onSurfaceVariant)
            textMeta()
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    private fun styleProps(n: CanvasNode) {
        propField(getString(R.string.canvas_prop_name), n.name, false, false) { v ->
            commitSelected { it.copy(name = v.take(128)) }
        }
        propFieldPair(
            getString(R.string.canvas_prop_rotation), formatNumber(n.rotation),
            { raw ->
                parseNumber(raw)?.let { r -> commitSelected { it.withNumber("rotation", r) } }
            },
            getString(R.string.canvas_prop_alpha), formatNumber(n.alpha),
            { raw ->
                parseNumber(raw)?.let { a ->
                    commitSelected { it.withNumber("alpha", a.coerceIn(0f, 1f)) }
                }
            }
        )
        paintProps(n)
        boolField(getString(R.string.canvas_prop_locked), n.locked) { b ->
            commitSelected { it.copy(locked = b) }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    private fun paintProps(n: CanvasNode) {
        if (n.type == CanvasSpec.TYPE_RECT || n.type == CanvasSpec.TYPE_ROUND_RECT ||
            n.type == CanvasSpec.TYPE_CIRCLE || n.type == CanvasSpec.TYPE_ELLIPSE ||
            n.type == CanvasSpec.TYPE_PATH || n.type == CanvasSpec.TYPE_INPUT ||
            n.type == CanvasSpec.TYPE_SELECT || n.type == CanvasSpec.TYPE_TOGGLE ||
            n.type == CanvasSpec.TYPE_BUTTON
        ) {
            colorField(getString(R.string.canvas_prop_fill), n.fill) { v ->
                commitSelected { it.withString("fill", v) }
            }
        }
        if (n.type == CanvasSpec.TYPE_TEXT || n.type == CanvasSpec.TYPE_IMAGE) return
        colorField(getString(R.string.canvas_prop_stroke), n.stroke) { v ->
            commitSelected { it.withString("stroke", v) }
        }
        propField(getString(R.string.canvas_prop_stroke_width), formatNumber(n.strokeWidth), false, true) { v ->
            parseNumber(v)?.let { w -> commitSelected { it.withNumber("strokeWidth", w) } }
        }
        if (n.type == CanvasSpec.TYPE_RECT || n.type == CanvasSpec.TYPE_ROUND_RECT ||
            n.type == CanvasSpec.TYPE_PROGRESS || n.type == CanvasSpec.TYPE_INPUT ||
            n.type == CanvasSpec.TYPE_SELECT || n.type == CanvasSpec.TYPE_TOGGLE ||
            n.type == CanvasSpec.TYPE_BUTTON
        ) {
            propField(getString(R.string.canvas_prop_radius), formatNumber(n.radius), false, true) { v ->
                parseNumber(v)?.let { r -> commitSelected { it.withNumber("radius", r) } }
            }
        }
        boolField(getString(R.string.canvas_prop_shadow), n.shadow) { b ->
            commitSelected { it.copy(shadow = b) }
        }
    }

    private fun textProps(n: CanvasNode) {
        propField(getString(R.string.canvas_prop_text), n.text, true, false) { v ->
            commitSelected { it.copy(text = CanvasSpec.safeText(v)) }
        }
        propField(getString(R.string.canvas_prop_text_size), formatNumber(n.textSize), false, true) { v ->
            parseNumber(v)?.let { s -> commitSelected { it.withNumber("textSize", s) } }
        }
        colorField(getString(R.string.canvas_prop_text_color), n.textColor) { v ->
            commitSelected { it.withString("textColor", v) }
        }
        propsBox.addView(hintView(getString(R.string.canvas_prop_align)))
        val alignRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingMd(0, 0, 0, 6)
        }
        val alignments = listOf(
            CanvasSpec.ALIGN_LEFT to R.string.canvas_align_left,
            CanvasSpec.ALIGN_CENTER to R.string.canvas_align_center,
            CanvasSpec.ALIGN_RIGHT to R.string.canvas_align_right
        )
        alignments.forEachIndexed { index, (align, labelRes) ->
            alignRow.addView(
                miniButton(getString(labelRes)) { commitSelected { it.copy(textAlign = align) } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index < alignments.lastIndex) marginEnd = dpPx(4)
                }
            )
        }
        propsBox.addView(alignRow)
        boolField(getString(R.string.canvas_prop_bold), n.bold) { b -> commitSelected { it.copy(bold = b) } }
        boolField(getString(R.string.canvas_prop_italic), n.italic) { b -> commitSelected { it.copy(italic = b) } }
        boolField(getString(R.string.canvas_prop_mono), n.monospace) { b -> commitSelected { it.copy(monospace = b) } }
        boolField(getString(R.string.canvas_prop_wrap), n.wrap) { b -> commitSelected { it.copy(wrap = b) } }
        boolField(getString(R.string.canvas_prop_ansi), n.ansi) { b -> commitSelected { it.copy(ansi = b) } }
    }

    private fun inputProps(n: CanvasNode) {
        propField(getString(R.string.canvas_prop_key), n.key, false, false) { v ->
            commitSelected { it.copy(key = v.trim().take(128)) }
        }
        propField(getString(R.string.canvas_prop_label), n.label, false, false) { v ->
            commitSelected { it.copy(label = v.take(512)) }
        }
        propField(getString(R.string.canvas_prop_text), n.text, true, false) { v ->
            commitSelected { it.copy(text = CanvasSpec.safeText(v)) }
        }
        boolField(getString(R.string.canvas_prop_password), n.password) { b ->
            commitSelected { it.copy(password = b) }
        }
        boolField(getString(R.string.canvas_prop_multiline), n.multiline) { b ->
            commitSelected { it.copy(multiline = b) }
        }
        propField(getString(R.string.canvas_prop_text_size), formatNumber(n.textSize), false, true) { v ->
            parseNumber(v)?.let { s -> commitSelected { it.withNumber("textSize", s) } }
        }
        colorField(getString(R.string.canvas_prop_text_color), n.textColor) { v ->
            commitSelected { it.withString("textColor", v) }
        }
        colorField(getString(R.string.canvas_prop_label_color), n.labelColor) { v ->
            commitSelected { it.withString("labelColor", v) }
        }
    }

    private fun selectProps(n: CanvasNode) {
        propField(getString(R.string.canvas_prop_key), n.key, false, false) { v ->
            commitSelected { it.copy(key = v.trim().take(128)) }
        }
        propField(getString(R.string.canvas_prop_label), n.label, false, false) { v ->
            commitSelected { it.copy(label = v.take(512)) }
        }
        propField(
            getString(R.string.canvas_prop_options),
            n.options.joinToString("\n"),
            true,
            false
        ) { v ->
            val options = v.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(CanvasSpec.MAX_OPTIONS)
            commitSelected { it.copy(options = options) }
        }
        boolField(getString(R.string.canvas_prop_multi), n.multi) { b ->
            commitSelected { it.copy(multi = b) }
        }
        propField(getString(R.string.canvas_prop_text_size), formatNumber(n.textSize), false, true) { v ->
            parseNumber(v)?.let { s -> commitSelected { it.withNumber("textSize", s) } }
        }
        colorField(getString(R.string.canvas_prop_text_color), n.textColor) { v ->
            commitSelected { it.withString("textColor", v) }
        }
        colorField(getString(R.string.canvas_prop_bar), n.barColor) { v ->
            commitSelected { it.withString("barColor", v) }
        }
        colorField(getString(R.string.canvas_prop_label_color), n.labelColor) { v ->
            commitSelected { it.withString("labelColor", v) }
        }
    }

    private fun toggleProps(n: CanvasNode) {
        propField(getString(R.string.canvas_prop_key), n.key, false, false) { v ->
            commitSelected { it.copy(key = v.trim().take(128)) }
        }
        propField(getString(R.string.canvas_prop_label), n.label, false, false) { v ->
            commitSelected { it.copy(label = v.take(512)) }
        }
        boolField(getString(R.string.canvas_prop_checked), n.checked) { b ->
            commitSelected { it.copy(checked = b) }
        }
        propField(getString(R.string.canvas_prop_filter), n.filter, false, false) { v ->
            commitSelected { it.copy(filter = v.trim().take(128)) }
        }
        colorField(getString(R.string.canvas_prop_label_color), n.labelColor) { v ->
            commitSelected { it.withString("labelColor", v) }
        }
        colorField(getString(R.string.canvas_prop_text_color), n.textColor) { v ->
            commitSelected { it.withString("textColor", v) }
        }
        colorField(getString(R.string.canvas_prop_bar), n.barColor) { v ->
            commitSelected { it.withString("barColor", v) }
        }
    }

    private fun buttonProps(n: CanvasNode) {
        propField(getString(R.string.canvas_prop_text), n.text, false, false) { v ->
            commitSelected { it.copy(text = CanvasSpec.safeText(v)) }
        }
        propsBox.addView(hintView(getString(R.string.canvas_prop_button_kind)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val kinds = listOf(
            ScriptDialogSpec.BTN_NORMAL to R.string.canvas_button_normal,
            ScriptDialogSpec.BTN_PRIMARY to R.string.canvas_button_primary,
            ScriptDialogSpec.BTN_DANGER to R.string.canvas_button_danger
        )
        kinds.forEachIndexed { index, (kind, label) ->
            row.addView(
                miniButton(getString(label)) { commitSelected { it.copy(buttonKind = kind) } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index < kinds.lastIndex) marginEnd = dpPx(4)
                }
            )
        }
        propsBox.addView(row)
        propField(getString(R.string.canvas_prop_text_size), formatNumber(n.textSize), false, true) { v ->
            parseNumber(v)?.let { s -> commitSelected { it.withNumber("textSize", s) } }
        }
        colorField(getString(R.string.canvas_prop_text_color), n.textColor) { v ->
            commitSelected { it.withString("textColor", v) }
        }
        boolField(getString(R.string.canvas_prop_bold), n.bold) { b ->
            commitSelected { it.copy(bold = b) }
        }
    }

    private fun imageProps(n: CanvasNode) {
        propField(getString(R.string.canvas_prop_src), n.src, false, false) { v ->
            commitSelected { it.copy(src = v.take(CanvasSpec.MAX_SRC)) }
        }
        propsBox.addView(
            miniButton(getString(R.string.canvas_choose_image)) { pickImage() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        propsBox.addView(hintView(getString(R.string.canvas_prop_fit)))
        val fitRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingMd(0, 0, 0, 6)
        }
        val fits = listOf(CanvasSpec.FIT_CONTAIN, CanvasSpec.FIT_COVER, CanvasSpec.FIT_STRETCH)
        fits.forEachIndexed { index, fit ->
            fitRow.addView(
                miniButton(fit) { commitSelected { it.copy(fit = fit) } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index < fits.lastIndex) marginEnd = dpPx(4)
                }
            )
        }
        propsBox.addView(fitRow)
        colorField(getString(R.string.canvas_prop_tint), n.tint) { v ->
            commitSelected { it.withString("tint", v) }
        }
    }

    private fun progressProps(n: CanvasNode) {
        propField(getString(R.string.canvas_prop_progress), formatNumber(n.progress), false, true) { v ->
            parseNumber(v)?.let { p -> commitSelected { it.withNumber("progress", p.coerceIn(0f, 1f)) } }
        }
        colorField(getString(R.string.canvas_prop_track), n.trackColor) { v ->
            commitSelected { it.withString("trackColor", v) }
        }
        colorField(getString(R.string.canvas_prop_bar), n.barColor) { v ->
            commitSelected { it.withString("barColor", v) }
        }
        colorField(getString(R.string.canvas_prop_gradient_from), n.gradientFrom) { v ->
            commitSelected { it.withString("gradientFrom", v) }
        }
        colorField(getString(R.string.canvas_prop_gradient_to), n.gradientTo) { v ->
            commitSelected { it.withString("gradientTo", v) }
        }
        boolField(getString(R.string.canvas_prop_show_label), n.showLabel) { b ->
            commitSelected { it.copy(showLabel = b) }
        }
        propField(getString(R.string.canvas_prop_label_format), n.labelFormat, false, false) { v ->
            commitSelected { it.copy(labelFormat = CanvasSpec.safeText(v)) }
        }
        colorField(getString(R.string.canvas_prop_label_color), n.labelColor) { v ->
            commitSelected { it.withString("labelColor", v) }
        }
        propField(getString(R.string.canvas_prop_label_size), formatNumber(n.labelSize), false, true) { v ->
            parseNumber(v)?.let { s -> commitSelected { it.withNumber("labelSize", s) } }
        }
    }

    private fun pathProps(n: CanvasNode) {
        propsBox.addView(hintView(getString(R.string.canvas_path_points_fmt, n.points.size)))
        propsBox.addView(
            miniButton(getString(R.string.canvas_import_path)) { showPathDialog() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        val pointTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingMd(0, 2, 0, 6)
        }
        pointTools.addView(
            miniButton(getString(R.string.canvas_path_add_point)) { insertPathMidpoint() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpPx(4)
            }
        )
        pointTools.addView(
            miniButton(getString(R.string.canvas_path_remove_point)) { removeLastPathPoint() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        propsBox.addView(pointTools)
        if (n.type == CanvasSpec.TYPE_PATH) {
            boolField(getString(R.string.canvas_prop_closed), n.closed) { b ->
                commitSelected { it.copy(closed = b) }
            }
            propField(getString(R.string.canvas_prop_trim), formatNumber(n.trim), false, true) { v ->
                parseNumber(v)?.let { t -> commitSelected { it.withNumber("trim", t) } }
            }
        }
        propField(getString(R.string.canvas_prop_dash), formatNumber(n.dash), false, true) { v ->
            parseNumber(v)?.let { d -> commitSelected { it.withNumber("dash", d) } }
        }
        propField(getString(R.string.canvas_prop_dash_gap), formatNumber(n.dashGap), false, true) { v ->
            parseNumber(v)?.let { d -> commitSelected { it.withNumber("dashGap", d) } }
        }
    }

    /** 在当前末段中间插入一个可拖拽点；不足两点时补一条对角基础线。 */
    @Suppress("ReturnCount")
    private fun insertPathMidpoint() {
        val n = host.selectedId?.let { scene().findNode(it) } ?: return
        if (n.points.size >= CanvasSpec.MAX_POINTS) {
            toast(getString(R.string.canvas_point_limit, CanvasSpec.MAX_POINTS))
            return
        }
        if (n.points.size < 2) {
            commitSelected(refresh = true) {
                it.copy(
                    points = listOf(CanvasPoint(0f, 0f), CanvasPoint(max(it.w, 1f), max(it.h, 1f))),
                    d = ""
                )
            }
            return
        }
        val a = n.points[n.points.size - 2]
        val b = n.points.last()
        val mid = CanvasPoint((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        val pts = n.points.toMutableList().apply { add(size - 1, mid) }
        commitSelected(refresh = true) { it.copy(points = pts, d = "") }
    }

    private fun removeLastPathPoint() {
        val n = host.selectedId?.let { scene().findNode(it) } ?: return
        if (n.points.size <= 2) {
            toast(getString(R.string.canvas_path_need_points))
            return
        }
        commitSelected(refresh = true) { it.copy(points = it.points.dropLast(1), d = "") }
    }

    // ================= 点按动作 =================

    private fun actionSummary(action: String): String {
        val a = action.trim()
        val submit = CanvasAction.parseSubmit(a)
        return if (a.isEmpty()) {
            getString(R.string.canvas_action_empty)
        } else if (a == "none") {
            getString(R.string.canvas_action_none)
        } else if (submit != null) {
            val keep = if (submit.keepOpen) " · " + getString(R.string.canvas_action_keep_short) else ""
            getString(R.string.canvas_action_submit) + " → " + submit.id + keep
        } else {
            val parts = a.split(":")
            val id = parts.getOrNull(1).orEmpty()
            when (parts[0]) {
                "show" -> getString(R.string.canvas_action_show) + " → " + id
                "hide" -> getString(R.string.canvas_action_hide) + " → " + id
                "toggle" -> getString(R.string.canvas_action_toggle) + " → " + id
                "select" -> getString(R.string.canvas_action_select) + " → " + id
                "progress" -> getString(R.string.canvas_action_progress) + " → " + id + " = " +
                    parts.getOrNull(2).orEmpty()
                "play" -> getString(R.string.canvas_action_play)
                "pause" -> getString(R.string.canvas_action_pause)
                "seek" -> getString(R.string.canvas_action_seek) + " " + id + " ms"
                else -> a
            }
        }
    }

    private fun setNodeAction(action: String) {
        commitSelected(refresh = true) { it.copy(action = action.take(256)) }
    }

    private fun showActionDialog(n: CanvasNode) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(20, 4, 20, 4)
        }
        var dialog: AlertDialog? = null
        fun option(titleRes: Int, descRes: Int, action: () -> Unit) {
            val row = actionOptionRow(getString(titleRes), getString(descRes))
            row.setOnClickListener {
                dialog?.dismiss()
                action()
            }
            box.addView(row)
        }
        option(R.string.canvas_action_submit, R.string.canvas_action_submit_desc) {
            showSubmitActionDialog(n)
        }
        option(R.string.canvas_action_local_title, R.string.canvas_action_local_desc) {
            showLocalActionDialog()
        }
        option(R.string.canvas_action_none, R.string.canvas_action_none_desc) {
            setNodeAction("none")
        }
        option(R.string.canvas_action_custom, R.string.canvas_action_custom_desc) {
            promptValue(
                R.string.canvas_action_custom,
                R.string.canvas_action_custom_hint,
                n.action.takeIf { it != "none" }.orEmpty(),
                false
            ) { raw ->
                val action = raw.trim()
                if (!isValidAction(action)) {
                    toast(getString(R.string.canvas_action_invalid))
                } else {
                    setNodeAction(if (action.isEmpty()) "none" else action)
                }
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.canvas_action_title))
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    private fun actionOptionRow(title: String, desc: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(10, 8, 10, 8)
            roundedBg(theme.surfaceContainer, UiTokens.CORNER_SM)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpPx(6) }
            isClickable = true
            isFocusable = true
            addView(TextView(this@CanvasDesignerActivity).apply {
                text = title
                setTextColor(theme.onSurface)
                textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY, bold = true)
            })
            addView(TextView(this@CanvasDesignerActivity).apply {
                text = desc
                setTextColor(theme.onSurfaceVariant)
                textMeta()
                setPaddingMd(0, 2, 0, 0)
            })
        }

    private fun showLocalActionDialog() {
        val options = listOf(
            "show" to R.string.canvas_action_show,
            "hide" to R.string.canvas_action_hide,
            "toggle" to R.string.canvas_action_toggle,
            "progress" to R.string.canvas_action_progress,
            "play" to R.string.canvas_action_play,
            "pause" to R.string.canvas_action_pause,
            "seek" to R.string.canvas_action_seek
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.canvas_action_local_pick))
            .setItems(options.map { getString(it.second) }.toTypedArray()) { _, which ->
                performLocalAction(options[which].first)
            }
            .showStyled(theme)
    }

    private fun performLocalAction(type: String) {
        when (type) {
            "play", "pause" -> setNodeAction(type)
            "seek" -> promptValue(
                R.string.canvas_action_seek, R.string.canvas_action_seek_ms, "", true
            ) { raw ->
                val ms = raw.toLongOrNull()
                if (ms == null || ms < 0) {
                    toast(getString(R.string.canvas_action_invalid))
                } else {
                    setNodeAction("seek:$ms")
                }
            }
            "progress" -> pickNode { id ->
                promptValue(
                    R.string.canvas_action_progress,
                    R.string.canvas_action_progress_value,
                    "0.5",
                    true
                ) { raw ->
                    val v = raw.toFloatOrNull()
                    if (v == null || v !in 0f..1f) {
                        toast(getString(R.string.canvas_action_invalid))
                    } else {
                        setNodeAction("progress:$id:${formatNumber(v)}")
                    }
                }
            }
            else -> pickNode { id -> setNodeAction("$type:$id") }
        }
    }

    private fun pickNode(onPick: (String) -> Unit) {
        val nodes = scene().flatten()
        if (nodes.isEmpty()) {
            toast(getString(R.string.canvas_action_need_node))
            return
        }
        val labels = nodes.map { n ->
            (if (host.selectedId == n.id) "● " else "") + n.name.ifBlank { n.id } + "  ·  " + n.id
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.canvas_action_pick_node))
            .setItems(labels) { _, which -> onPick(nodes[which].id) }
            .showStyled(theme)
    }

    private fun promptValue(
        titleRes: Int,
        hintRes: Int,
        initial: String,
        numeric: Boolean,
        onOk: (String) -> Unit
    ) {
        val edit = EditText(this).apply {
            setText(initial)
            hint = getString(hintRes)
            setHintTextColor(theme.onSurfaceVariant)
            setTextColor(theme.onSurface)
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_COMPACT)
            setPaddingMd(10, 6, 10, 6)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            if (numeric) {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(20, 8, 20, 8)
        }
        box.addView(edit)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(titleRes))
            .setView(box)
            .setPositiveButton(getString(R.string.save), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val raw = edit.text.toString().trim()
            if (raw.isEmpty()) {
                toast(getString(R.string.canvas_action_invalid))
                return@setOnClickListener
            }
            dialog.dismiss()
            onOk(raw)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun showSubmitActionDialog(n: CanvasNode) {
        val existing = CanvasAction.parseSubmit(n.action)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(20, 8, 20, 8)
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.canvas_action_submit_id)
            setTextColor(theme.onSurfaceVariant)
            textMeta()
        })
        val idEdit = EditText(this).apply {
            setText(existing?.id.orEmpty())
            setHint(getString(R.string.canvas_action_submit_id_hint))
            setHintTextColor(theme.onSurfaceVariant)
            setTextColor(theme.onSurface)
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_COMPACT)
            setPaddingMd(10, 6, 10, 6)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        box.addView(idEdit)
        val keep = CheckBox(this).apply {
            text = getString(R.string.canvas_action_keep)
            isChecked = existing?.keepOpen ?: false
            setTextColor(theme.onSurface)
            textCompact()
        }
        box.addView(keep)
        val preview = TextView(this).apply {
            setTextColor(theme.primaryVisible)
            textMeta()
            typeface = Typeface.MONOSPACE
            setPaddingMd(0, 4, 0, 0)
        }
        box.addView(preview)
        fun actionFor(id: String): String =
            if (id.isEmpty()) "" else "submit:$id" + if (keep.isChecked) ":keep" else ""
        fun updatePreview() {
            val action = actionFor(idEdit.text.toString().trim())
            preview.text = if (action.isEmpty()) {
                getString(R.string.canvas_action_empty)
            } else {
                getString(R.string.canvas_action_preview_fmt, action)
            }
        }
        idEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updatePreview()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        keep.setOnCheckedChangeListener { _, _ -> updatePreview() }
        updatePreview()
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.canvas_action_submit))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(getString(R.string.save), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val id = idEdit.text.toString().trim()
            if (!isValidSubmitId(id)) {
                toast(getString(R.string.canvas_action_invalid))
                return@setOnClickListener
            }
            val action = actionFor(id)
            dialog.dismiss()
            setNodeAction(action)
        }
    }

    private fun isValidSubmitId(id: String): Boolean =
        id.isNotEmpty() && id.length <= CanvasSpec.MAX_ID &&
            id.none { it == ':' || it.isWhitespace() }

    private fun isValidAction(action: String): Boolean {
        val a = action.trim()
        return if (a.isEmpty() || a == "none") {
            true
        } else if (CanvasAction.parseSubmit(a) != null) {
            true
        } else {
            val parts = a.split(":")
            when (parts[0]) {
                "show", "hide", "toggle", "select" -> parts.getOrNull(1)?.isNotBlank() == true
                "progress" -> parts.getOrNull(1)?.isNotBlank() == true &&
                    parts.getOrNull(2)?.toFloatOrNull()?.let { it in 0f..1f } == true
                "play", "pause" -> true
                "seek" -> (parts.getOrNull(1)?.toLongOrNull() ?: -1L) >= 0L
                else -> false
            }
        }
    }

    // ================= 帮助 =================

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.canvas_help_title))
            .setMessage(getString(R.string.canvas_help_text))
            .setPositiveButton(getString(R.string.sc_confirm), null)
            .showStyled(theme)
    }

    // ================= 路径导入 =================

    private fun showPathDialog() {
        val edit = EditText(this).apply {
            setTextColor(theme.onSurface)
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_COMPACT)
            setPaddingMd(12, 8, 12, 8)
            minLines = 6
            gravity = Gravity.TOP
            setHint(getString(R.string.canvas_path_hint))
            setHintTextColor(theme.onSurfaceVariant)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(20, 8, 20, 8)
        }
        box.addView(edit)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.canvas_path_dialog_title))
            .setView(box)
            .setPositiveButton(getString(R.string.canvas_json_import), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val raw = edit.text.toString().trim()
            val parsed = parsePathInput(raw)
            if (parsed == null || parsed.first.size < 2) {
                toast(getString(R.string.canvas_path_invalid))
                return@setOnClickListener
            }
            dialog.dismiss()
            val pts = parsed.first
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (p in pts) {
                minX = min(minX, p.x)
                minY = min(minY, p.y)
                maxX = max(maxX, p.x)
                maxY = max(maxY, p.y)
            }
            val local = pts.map { CanvasPoint(it.x - minX, it.y - minY) }
            commitSelected(refresh = true) {
                it.copy(
                    points = local,
                    x = minX,
                    y = minY,
                    w = max(maxX - minX, 1f),
                    h = max(maxY - minY, 1f),
                    closed = parsed.second || it.closed,
                    d = ""
                )
            }
        }
    }

    /** 支持 SVG path 子集，或 JSON：点数组 / {points:[...]} / 路径节点对象。 */
    private fun parsePathInput(raw: String): Pair<List<CanvasPoint>, Boolean>? {
        if (raw.isEmpty()) return null
        if (raw.startsWith("[")) {
            val scene = runCatching {
                CanvasSceneCodec.parseScene("{\"nodes\":[{\"type\":\"path\",\"points\":$raw}]}")
            }.getOrNull()
            val node = scene?.nodes?.firstOrNull()
            if (node != null && node.points.size >= 2) return node.points to node.closed
        }
        if (raw.startsWith("{")) {
            val scene = runCatching {
                if (raw.contains("\"nodes\"")) CanvasSceneCodec.parseScene(raw)
                else CanvasSceneCodec.parseScene("{\"nodes\":[$raw]}")
            }.getOrNull()
            val node = scene?.nodes?.firstOrNull()
            if (node != null && node.points.size >= 2) return node.points to node.closed
            return null
        }
        val parsed = CanvasPath.parse(raw)
        if (parsed.points.size < 2) return null
        return parsed.points to parsed.closed
    }

    // ================= 动画与播放 =================

    private fun animations(): List<CanvasAnimation> = scene().animations

    private fun hasAnimations(): Boolean = animations().isNotEmpty()

    private fun hasInfiniteAnimations(): Boolean = CanvasPlayback.hasInfinite(animations())

    /** 只统计会结束的动画；无限循环动画不参与结束时间。 */
    private fun finiteEndMs(): Long = CanvasPlayback.finiteEndMs(animations())

    /** 进度条、时间标签和 seek 共用同一个显示时间，永远落在 [0, end] 内。 */
    private fun displayTimeMs(): Long {
        val end = finiteEndMs()
        if (end <= 0) return 0
        return host.currentTimeMs().coerceIn(0L, end)
    }

    private fun refreshTimeline() {
        timelineBox.removeAllViews()
        val anims = animations()
        if (anims.isEmpty()) {
            timelineBox.addView(hintView(getString(R.string.canvas_no_anim)))
        } else {
            for (i in anims.indices) {
                val a = anims[i]
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPaddingMd(8, 6, 8, 6)
                    roundedBg(theme.surfaceContainer, UiTokens.CORNER_SM)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dpPx(4) }
                    setOnClickListener { showAnimationDialog(i) }
                }
                row.addView(TextView(this).apply {
                    text = animationSummary(a)
                    setTextColor(theme.onSurface)
                    textCompact()
                    typeface = Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(squareButton(
                    "×", getString(R.string.canvas_layer_delete), sizeDp = 36
                ) { deleteAnimation(i) })
                timelineBox.addView(row)
            }
        }
        refreshTimeUi()
    }

    private fun animationSummary(a: CanvasAnimation): String {
        val target = (if (a.targets.isNotEmpty()) a.targets else listOf(a.target))
            .filter { it.isNotBlank() }
            .joinToString(",")
            .ifBlank { getString(R.string.canvas_anim_targets_none) }
        val base = getString(R.string.canvas_anim_summary_fmt, target, a.property, a.durationMs)
        return when {
            a.repeat <= 0 -> base + " · " + getString(R.string.canvas_anim_loop)
            a.repeat > 1 -> base + getString(R.string.canvas_anim_repeat_fmt, a.repeat)
            else -> base
        }
    }

    private fun refreshTimeUi() {
        if (!::seekBar.isInitialized || !::playBtn.isInitialized) return
        val has = hasAnimations()
        playBtn.isEnabled = has
        replayBtn.isEnabled = has
        playBtn.alpha = if (has) 1f else 0.4f
        replayBtn.alpha = if (has) 1f else 0.4f

        if (!has) {
            if (host.isPlaying()) host.pause()
            seekBar.visibility = View.GONE
            loopCheck.visibility = View.GONE
            timeLabel.text = getString(R.string.canvas_anim_no_anim_short)
            playBtn.text = getString(R.string.canvas_play)
            return
        }

        if (hasInfiniteAnimations()) {
            // 无限循环的相位没有结束点；不显示会无限增长的数字 / 进度条。
            seekBar.visibility = View.GONE
            loopCheck.visibility = View.GONE
            timeLabel.text = getString(R.string.canvas_anim_loop_playing)
        } else {
            val end = finiteEndMs()
            val max = end.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            seekBar.visibility = View.VISIBLE
            loopCheck.visibility = View.VISIBLE
            if (seekBar.max != max) seekBar.max = max
            val shown = displayTimeMs()
            if (!seekDragging) seekBar.progress = shown.coerceAtMost(max.toLong()).toInt()
            timeLabel.text = getString(R.string.canvas_time_fmt, formatTime(shown), formatTime(end))
        }
        playBtn.text = getString(if (host.isPlaying()) R.string.canvas_pause else R.string.canvas_play)
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(ticker)
        mainHandler.post(ticker)
    }

    private fun togglePlay() {
        if (!hasAnimations()) {
            refreshTimeUi()
            return
        }
        if (host.isPlaying()) {
            host.pause()
            refreshTimeUi()
            return
        }
        if (!hasInfiniteAnimations()) {
            val end = finiteEndMs()
            if (!loopPreview && end > 0 && host.currentTimeMs() >= end) host.seek(0)
        }
        host.play()
        startTicker()
        refreshTimeUi()
    }

    /** 重播固定为 seek(0) + play()，从循环中段也能回到起点。 */
    private fun replayAnimations() {
        if (!hasAnimations()) return
        host.seek(0)
        host.play()
        startTicker()
        refreshTimeUi()
    }

    private fun deleteAnimation(index: Int) {
        val s = scene()
        if (index !in s.animations.indices) return
        pushUndo(s)
        val list = s.animations.toMutableList().apply { removeAt(index) }
        host.setScene(s.copy(animations = list))
        refreshPanels()
    }

    private data class PresetAnim(
        val property: String,
        val from: Float,
        val to: Float,
        val durationMs: Int = 700,
        val repeat: Int = 1,
        val alternate: Boolean = false,
        val secondProperty: String? = null
    )

    private fun chooseAnimationPreset() {
        if (scene().nodes.isEmpty()) {
            toast(getString(R.string.canvas_anim_need_node))
            return
        }
        val presets = listOf(
            "fade_in" to R.string.canvas_anim_preset_fade_in,
            "fade_out" to R.string.canvas_anim_preset_fade_out,
            "slide_up" to R.string.canvas_anim_preset_slide_up,
            "slide_left" to R.string.canvas_anim_preset_slide_left,
            "progress" to R.string.canvas_anim_preset_progress,
            "spin" to R.string.canvas_anim_preset_spin,
            "pulse" to R.string.canvas_anim_preset_pulse,
            "custom" to R.string.canvas_anim_preset_custom
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.canvas_anim_preset_title))
            .setItems(presets.map { getString(it.second) }.toTypedArray()) { _, which ->
                showAnimationDialog(null, presets[which].first)
            }
            .showStyled(theme)
    }

    private fun presetFor(key: String, n: CanvasNode): PresetAnim? = when (key) {
        "fade_in" -> PresetAnim("alpha", 0f, 1f)
        "fade_out" -> PresetAnim("alpha", 1f, 0f)
        "slide_up" -> PresetAnim("y", n.y + 32f, n.y, 600)
        "slide_left" -> PresetAnim("x", n.x - 32f, n.x, 600)
        "progress" -> PresetAnim("progress", 0f, 1f, 1200)
        "spin" -> PresetAnim("rotation", n.rotation, n.rotation + 360f, 1200)
        "pulse" -> PresetAnim(
            "scaleX", n.scaleX, max(n.scaleX, 1f) * 1.15f, 500, repeat = -1,
            alternate = true, secondProperty = "scaleY"
        )
        else -> null
    }

    private fun animationField(box: LinearLayout, labelRes: Int, value: String, numeric: Boolean = false): EditText {
        box.addView(TextView(this).apply {
            text = getString(labelRes)
            setTextColor(theme.onSurfaceVariant)
            textMeta()
            setPaddingMd(0, 6, 0, 2)
        })
        val e = EditText(this).apply {
            setText(value)
            setTextColor(theme.onSurface)
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_COMPACT)
            setPaddingMd(10, 6, 10, 6)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_NEXT
            if (numeric) {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
            }
        }
        box.addView(e)
        return e
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
    private fun showAnimationDialog(editIndex: Int?, preset: String? = null) {
        val existing = editIndex?.let { scene().animations.getOrNull(it) }
        val startNode = host.selectedId?.let { scene().findNode(it) } ?: scene().nodes.firstOrNull()
        val presetValues = if (existing == null && preset != null && startNode != null) {
            presetFor(preset, startNode)
        } else {
            null
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(20, 4, 20, 8)
        }
        if (presetValues != null) box.addView(hintView(getString(R.string.canvas_anim_preset_hint)))

        val initialTargets = when {
            existing == null -> host.selectedId ?: startNode?.id.orEmpty()
            existing.targets.isNotEmpty() -> existing.targets.joinToString(",")
            else -> existing.target
        }
        val targetEdit = EditText(this).apply {
            setText(initialTargets)
            setTextColor(theme.onSurface)
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_COMPACT)
            setPaddingMd(10, 6, 10, 6)
            isSingleLine = true
            hint = getString(R.string.canvas_anim_targets_none)
            setHintTextColor(theme.onSurfaceVariant)
        }
        val targetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        targetRow.addView(TextView(this).apply {
            text = getString(R.string.canvas_anim_target)
            setTextColor(theme.onSurfaceVariant)
            textMeta()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        targetRow.addView(miniButton(getString(R.string.canvas_anim_target_pick)) {
            pickNode { id -> targetEdit.setText(id) }
        })
        box.addView(targetRow)
        box.addView(targetEdit)

        val propertyEdit = animationField(
            box, R.string.canvas_anim_property,
            presetValues?.property ?: existing?.property ?: "alpha"
        )
        val fromEdit = animationField(
            box, R.string.canvas_anim_from,
            presetValues?.from?.let { formatNumber(it) } ?: existing?.from?.let { formatNumber(it) } ?: "",
            numeric = true
        )
        val toEdit = animationField(
            box, R.string.canvas_anim_to,
            presetValues?.to?.let { formatNumber(it) } ?: existing?.to?.let { formatNumber(it) } ?: "",
            numeric = true
        )
        val durationEdit = animationField(
            box, R.string.canvas_anim_duration,
            (presetValues?.durationMs ?: existing?.durationMs ?: 600).toString(), numeric = true
        )
        val loop = CheckBox(this).apply {
            text = getString(R.string.canvas_anim_loop)
            isChecked = (presetValues?.repeat ?: existing?.repeat ?: 1) <= 0
            setTextColor(theme.onSurface)
            textCompact()
        }
        val alternate = CheckBox(this).apply {
            text = getString(R.string.canvas_anim_alternate)
            isChecked = presetValues?.alternate ?: existing?.alternate ?: false
            setTextColor(theme.onSurface)
            textCompact()
        }
        box.addView(loop)
        box.addView(alternate)

        val advancedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val delayEdit = animationField(
            advancedBox, R.string.canvas_anim_delay, (existing?.delayMs ?: 0).toString(), numeric = true
        )
        val stagger = animationField(
            advancedBox, R.string.canvas_anim_stagger, (existing?.staggerMs ?: 0).toString(), numeric = true
        )
        val easing = animationField(advancedBox, R.string.canvas_anim_easing, existing?.easing ?: "ease-out")
        val fromColor = animationField(advancedBox, R.string.canvas_anim_from_color, existing?.fromColor.orEmpty())
        val toColor = animationField(advancedBox, R.string.canvas_anim_to_color, existing?.toColor.orEmpty())
        val fromPath = animationField(advancedBox, R.string.canvas_anim_from_path, existing?.fromPath.orEmpty())
        val toPath = animationField(advancedBox, R.string.canvas_anim_to_path, existing?.toPath.orEmpty())
        val advancedOpen = existing != null && (
            existing.delayMs > 0 || existing.staggerMs > 0 ||
                existing.fromColor.isNotEmpty() || existing.toColor.isNotEmpty() ||
                existing.fromPath.isNotEmpty() || existing.toPath.isNotEmpty() ||
                (existing.easing.isNotBlank() && existing.easing != "ease-out")
            )
        val advancedToggle = CheckBox(this).apply {
            text = getString(R.string.canvas_anim_advanced)
            isChecked = advancedOpen
            setTextColor(theme.onSurface)
            textCompact()
            setOnCheckedChangeListener { _, checked ->
                advancedBox.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        box.addView(advancedToggle)
        box.addView(advancedBox)
        advancedBox.visibility = if (advancedToggle.isChecked) View.VISIBLE else View.GONE

        val scroll = ScrollView(this).apply { addView(box) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(if (existing == null) R.string.canvas_anim_title_add else R.string.canvas_anim_title_edit))
            .setView(scroll)
            .setPositiveButton(getString(R.string.save), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val targets = targetEdit.text.toString()
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .take(CanvasSpec.MAX_TARGETS)
            if (targets.isEmpty()) {
                toast(getString(R.string.canvas_anim_need_target))
                return@setOnClickListener
            }
            if (targets.any { it != CanvasSpec.CAMERA_TARGET && scene().findNode(it) == null }) {
                toast(getString(R.string.canvas_anim_need_target))
                return@setOnClickListener
            }
            val property = propertyEdit.text.toString().trim().ifEmpty { "alpha" }
            val from = fromEdit.text.toString().trim().toFloatOrNull()
            val to = toEdit.text.toString().trim().toFloatOrNull()
            val fromColorValue = fromColor.text.toString().trim()
            val toColorValue = toColor.text.toString().trim()
            val fromPathValue = fromPath.text.toString().trim()
            val toPathValue = toPath.text.toString().trim()
            val hasColor = fromColorValue.isNotEmpty() && toColorValue.isNotEmpty()
            val hasPath = fromPathValue.isNotEmpty() && toPathValue.isNotEmpty()
            val hasKeyframes = !existing?.keyframes.isNullOrEmpty()
            val hasAnyValue = from != null || to != null || hasColor || hasPath || hasKeyframes
            if (!hasAnyValue) {
                toast(getString(R.string.canvas_anim_no_change))
                return@setOnClickListener
            }
            val duration = durationEdit.text.toString().trim().toIntOrNull()?.coerceIn(0, 600_000) ?: 600
            val delay = delayEdit.text.toString().trim().toIntOrNull()?.coerceIn(0, 600_000) ?: 0
            val staggerMs = stagger.text.toString().trim().toIntOrNull()?.coerceIn(0, 60_000) ?: 0
            // 设计器没有关键帧编辑 UI：只改时长/循环等非值字段时保留，改值就丢弃，避免
            // keyframes 悄悄覆盖用户刚填的 from/to。
            val keepKeyframes = existing != null && existing.keyframes.isNotEmpty() &&
                existing.property == property &&
                existing.from == from && existing.to == to &&
                existing.fromColor == fromColorValue && existing.toColor == toColorValue &&
                existing.fromPath == fromPathValue && existing.toPath == toPathValue
            if (existing != null && existing.keyframes.isNotEmpty() && !keepKeyframes) {
                toast(getString(R.string.canvas_anim_keyframes_dropped))
            }
            fun buildAnim(prop: String, id: String, start: Float?, end: Float?): CanvasAnimation =
                CanvasAnimation(
                    id = id,
                    target = if (targets.size == 1) targets[0] else "",
                    targets = if (targets.size > 1) targets else emptyList(),
                    property = prop,
                    from = start,
                    to = end,
                    fromColor = fromColorValue,
                    toColor = toColorValue,
                    fromPath = fromPathValue,
                    toPath = toPathValue,
                    durationMs = duration,
                    delayMs = delay,
                    repeat = if (loop.isChecked) -1 else (existing?.repeat?.takeIf { it > 1 } ?: 1),
                    alternate = alternate.isChecked,
                    easing = easing.text.toString().trim().ifEmpty { "ease-out" },
                    staggerMs = staggerMs,
                    keyframes = if (keepKeyframes) existing?.keyframes.orEmpty() else emptyList()
                )
            val s = scene()
            pushUndo(s)
            val list = s.animations.toMutableList()
            fun freeId(): String {
                val ids = list.map { it.id }.toHashSet()
                var i = list.size + 1
                while (ids.contains("a$i")) i++
                return "a$i"
            }
            val anim = buildAnim(property, existing?.id ?: freeId(), from, to)
            if (editIndex != null && editIndex in list.indices) {
                list[editIndex] = anim
            } else {
                list.add(anim)
                if (presetValues?.secondProperty != null && preset == "pulse") {
                    val second = buildAnim(presetValues.secondProperty, freeId(), presetValues.from, presetValues.to)
                    list.add(second)
                }
            }
            host.setScene(s.copy(animations = list), resetClock = true)
            host.play()
            currentTab = PanelTab.ANIM
            dialog.dismiss()
            refreshPanels()
            startTicker()
        }
    }

    // ================= 图片 =================

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, REQ_IMAGE) }
            .onFailure { toast(getString(R.string.canvas_image_pick_failed)) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            commitSelected(refresh = true) { it.copy(src = uri.toString()) }
        }
    }

    // ================= JSON / 命令 =================

    private fun showJsonDialog() {
        flushFocusedField()
        val editor = EditText(this).apply {
            setText(host.snapshotJson())
            setTextColor(theme.onSurface)
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_COMPACT)
            setPaddingMd(12, 8, 12, 8)
            gravity = Gravity.TOP
            minLines = 8
            maxLines = 16
            isSingleLine = false
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(20, 8, 20, 8)
        }
        box.addView(editor)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.canvas_json_title))
            .setView(box)
            .setPositiveButton(getString(R.string.canvas_json_import), null)
            .setNeutralButton(getString(R.string.canvas_json_copy), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            copyText(editor.text.toString())
            toast(getString(R.string.canvas_json_copied))
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val parsed = runCatching { CanvasSceneCodec.parseScene(editor.text.toString()) }.getOrNull()
            if (parsed == null) {
                toast(getString(R.string.canvas_json_invalid))
                return@setOnClickListener
            }
            dialog.dismiss()
            pushUndo(host.scene)
            pendingBeforeEdit = null
            host.setScene(parsed, resetClock = true)
            host.select(null)
            refreshPanels()
        }
    }

    private fun exportCommand() {
        flushFocusedField()
        val json = host.snapshotJson()
        val command = buildString {
            append("termlou-ui \\\n  --canvas ").append(shellQuote(json))
            append(" \\\n  --canvas-height ").append(rowHeightDp)
            if (canvasKey.isNotBlank()) {
                append(" \\\n  --canvas-key ").append(shellQuote(canvasKey))
            }
            if (host.scene.flatten().none { it.type == CanvasSpec.TYPE_BUTTON && it.visible }) {
                append(" \\\n  --button ").append(shellQuote(getString(R.string.canvas_ok_button)))
            }
        }
        copyText(command)
        toast(getString(R.string.canvas_cmd_copied))
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun colorHex(c: Int): String = "#%08X".format(c)

    private fun copyText(text: String) {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("termlou-canvas", text))
    }

    private fun finishWithScene() {
        flushFocusedField()
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_SCENE, host.snapshotJson())
                .putExtra(EXTRA_HEIGHT, rowHeightDp)
                .putExtra(EXTRA_KEY, canvasKey)
        )
        finish()
    }

    // ================= 状态 =================

    private fun refreshPanels() {
        if (!::layersBox.isInitialized) return
        refreshLayers()
        refreshProps()
        refreshTimeline()
        updateStatus()
        updateUndoRedo()
        updateEmptyState()
        updateTabStyles()
        renderPanel()
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        val s = scene()
        val sel = host.selectedId
        val name = sel?.let { id -> s.findNode(id)?.let { it.name.ifBlank { it.id } } }
            ?: getString(R.string.canvas_none)
        statusText.text = getString(
            R.string.canvas_status_fmt,
            s.flatten().size,
            s.width.toInt(),
            s.height.toInt(),
            name
        )
    }
}
