package com.workspace.proot

import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 笔记四页共享基座：主题（跟随夜间模式）、NotesStore、标题栏、顶部提示、
 * 分段键行、输入弹窗。子页只管摆自己的内容。
 */
abstract class NotesPageActivity : AppCompatActivity() {

    protected val theme: ThemeColors by lazy {
        ThemeColors.default(
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(NIGHT_KEY, true)
        )
    }

    protected val store: NotesStore by lazy {
        NotesStore(File(filesDir, "$WORKSPACE_DIR/$NOTES_DIR")).also { it.reload() }
    }

    private val hintHandler = Handler(Looper.getMainLooper())
    private var hintView: TextView? = null
    private var hintTask: Runnable? = null

    protected fun density(): Float = resources.displayMetrics.density

    /** 标题栏：surfaceVariant 底 + 左侧返回 + 标题；下方自带一条 transient 提示行。 */
    protected fun buildTitleBar(title: String): LinearLayout {
        val d = density()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.surfaceVariant)
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        }
        bar.addView(TextView(this).apply {
            text = getString(R.string.notes_back)
            setTextColor(theme.onSurface)
            textSize = UiTokens.TEXT_TITLE
            setPadding(0, 0, (12 * d).toInt(), 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = title
            setTextColor(theme.onSurface)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_TITLE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        hintView = TextView(this).apply {
            setTextColor(theme.onSurfaceVariant)
            textSize = UiTokens.TEXT_COMPACT
            maxLines = 1
            visibility = android.view.View.GONE
            setPadding(0, 0, 0, 0)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surfaceVariant)
            addView(bar)
            hintView?.let { hv ->
                val row = LinearLayout(this@NotesPageActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding((48 * d).toInt(), 0, (16 * d).toInt(), (6 * d).toInt())
                    addView(hv)
                }
                addView(row)
            }
        }
        return wrap
    }

    /** 顶部提示（如"文件空"）：显示后自动消失。 */
    protected fun showHint(text: String) {
        val hv = hintView ?: return
        hintTask?.let { hintHandler.removeCallbacks(it) }
        hv.text = text
        hv.visibility = android.view.View.VISIBLE
        val task = Runnable { hv.visibility = android.view.View.GONE }
        hintTask = task
        hintHandler.postDelayed(task, HINT_MS)
    }

    /** 分段键行里的一个键（外观由 applyRow 统一渲染）。 */
    protected fun segButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((8 * density()).toInt(), 0, (8 * density()).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

    /** 空心描边小键（工具行用，如 ＋标签 / ＋待办）。 */
    protected fun toolButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(theme.onSurface)
            textSize = UiTokens.TEXT_BODY
            setPadding((12 * density()).toInt(), (4 * density()).toInt(), (12 * density()).toInt(), (4 * density()).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (4 * density()).toInt()
                marginEnd = (4 * density()).toInt()
            }
            ButtonStyle.outlined(this, theme.outline)
            setOnClickListener { onClick() }
        }

    /** 列表行：主标题 + 副行小字，整行可点。 */
    protected fun textRow(title: String, sub: String, onClick: () -> Unit): LinearLayout {
        val d = density()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            addView(TextView(this@NotesPageActivity).apply {
                text = title
                setTextColor(theme.onSurface)
                textSize = UiTokens.TEXT_BODY
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (sub.isNotEmpty()) {
                addView(TextView(this@NotesPageActivity).apply {
                    text = sub
                    setTextColor(theme.onSurfaceVariant)
                    textSize = UiTokens.TEXT_META
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            setOnClickListener { onClick() }
        }
    }

    protected fun divider(): android.view.View = SegmentStyle.hDivider(this, theme.onSurface)

    /** 带滚动的内容列容器。 */
    protected fun scrollColumn(): Pair<ScrollView, LinearLayout> {
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(inner) }
        return scroll to inner
    }

    /**
     * 通用单输入弹窗（新建/重命名/改待办内容都用它）。
     * 确定后自动关闭再回调，无需调用方做校验（空名由调用方填时间戳）。
     */
    protected fun inputDialog(
        title: String,
        hint: String,
        initial: String,
        confirmText: String,
        onConfirm: (String) -> Unit
    ) {
        val d = density()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        val input = EditText(this).apply {
            this.hint = hint
            setText(initial)
            setSelection(initial.length)
            FieldStyle.applyOutlined(this, theme.outline, theme.onSurface, theme.onSurfaceVariant, UiTokens.TEXT_BODY)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(confirmText) { _, _ -> onConfirm(input.text?.toString().orEmpty()) }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    protected fun watchText(edit: EditText, onChange: () -> Unit) {
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onChange()
        })
    }

    override fun onDestroy() {
        hintTask?.let { hintHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    companion object {
        const val PREFS_NAME = "term-lou-settings"
        const val NIGHT_KEY = "nightMode"
        const val WORKSPACE_DIR = "workspace"
        const val NOTES_DIR = "Notes"
        const val EXTRA_OPEN = "open_note"
        private const val HINT_MS = 2200L
    }
}
