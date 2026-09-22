package com.workspace.proot

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
 * 笔记子页共享基座：主题（跟随夜间模式）、NotesStore、标题栏、
 * 分段键行、输入弹窗。子页只管摆自己的内容。
 */
abstract class NotesPageActivity : AppCompatActivity() {

    protected val theme: ThemeColors by lazy {
        ThemeColors.default(
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(NIGHT_KEY, true)
        )
    }

    private val storeLazy = lazy {
        NotesStore(File(filesDir, "$WORKSPACE_DIR/$NOTES_DIR")).also { it.reload() }
    }
    protected val store: NotesStore by storeLazy

    protected fun density(): Float = resources.displayMetrics.density

    /** 标题栏：surfaceVariant 底 + 左侧返回 + 标题。通知统一走主界面状态栏。
     * 独立笔记页传 showBack=false：只留标题，返回键直接退出。 */
    protected fun buildTitleBar(title: String, showBack: Boolean = true): LinearLayout {
        val d = density()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.surfaceVariant)
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            if (showBack) {
                addView(TextView(this@NotesPageActivity).apply {
                    text = getString(R.string.notes_back)
                    setTextColor(theme.onSurface)
                    textSize = UiTokens.TEXT_TITLE
                    setPadding(0, 0, (12 * d).toInt(), 0)
                    setOnClickListener { finish() }
                })
            }
            addView(TextView(this@NotesPageActivity).apply {
                text = title
                setTextColor(theme.onSurface)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textSize = UiTokens.TEXT_TITLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    /** 分段键行里的一个键（外观由 applyRow 统一渲染）。 */
    protected fun segButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = AppLang.bottomButtonTextSize(this@NotesPageActivity)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(3, 0, 3, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

    /** 标签胶囊：按标签内容算出的淡色填充 + 同色系描边 + 纯词（无 #）。
     * 超过字数阈值的长词循环滚动，短词静态包裹。 */
    protected fun tagCapsule(tag: String, onClick: () -> Unit): TextView {
        val d = density()
        val (fill, edge) = tagCapsuleColors(tag)
        return TextView(this).apply {
            text = tag
            setTextColor(TAG_TEXT_COLOR)
            textSize = UiTokens.TEXT_COMPACT
            maxLines = 1
            setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            if (tag.length > MARQUEE_CHARS) {
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = MARQUEE_FOREVER
                maxWidth = (MARQUEE_MAX_DP * d).toInt()
                isSelected = true
            } else {
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            background = GradientDrawable().apply {
                cornerRadius = (12 * d)
                setStroke((1 * d).toInt().coerceAtLeast(1), edge)
                setColor(fill)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * d).toInt() }
            setOnClickListener { onClick() }
        }
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
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_BODY)
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

    /** 笔记库连接随页面销毁关闭（三个子页共用此基座）。 */
    override fun onDestroy() {
        super.onDestroy()
        if (storeLazy.isInitialized()) runCatching { store.close() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 拼音表后台预热：首个搜索框击键前基本就绪，未就绪时拼音层自动跳过。
        PinyinDict.loadAsync { assets.open(PinyinDict.ASSET) }
    }

    companion object {
        const val PREFS_NAME = "term-lou-settings"
        const val NIGHT_KEY = "nightMode"
        const val WORKSPACE_DIR = "workspace"
        const val NOTES_DIR = "Notes"
        const val EXTRA_OPEN = "open_note"
        /** 独立笔记页跳子页时透传：子页点笔记回到独立页，不唤醒主界面/Linux。 */
        const val EXTRA_FROM_STANDALONE = "from_standalone"
    }
}

/**
 * 子页通知暂存：待办/标签是独立 Activity，状态栏被盖住看不见，
 * 通知先暂存，主界面 onResume 回来再弹。超期或进程重建自动丢弃。
 */
internal object NoteNotify {
    private var message: String? = null
    private var atMs: Long = 0L

    fun post(text: String) {
        message = text
        atMs = System.currentTimeMillis()
    }

    fun takeIfFresh(): String? {
        val msg = message ?: return null
        message = null
        if (System.currentTimeMillis() - atMs > FRESH_WINDOW_MS) return null
        return msg
    }

    private const val FRESH_WINDOW_MS = 60_000L
}

/**
 * 标签胶囊配色：色相由标签内容哈希决定（同标签天然同色），
 * 填充淡色 + 描边同色系加深。返回 (fill, edge)。
 */
internal fun tagCapsuleColors(tag: String): Pair<Int, Int> {
    val hue = ((tag.hashCode() and 0x7fffffff) % HUE_BUCKETS).toFloat()
    val fill = android.graphics.Color.HSVToColor(floatArrayOf(hue, FILL_SAT, FILL_VAL))
    val edge = android.graphics.Color.HSVToColor(floatArrayOf(hue, EDGE_SAT, EDGE_VAL))
    return fill to edge
}

private const val HUE_BUCKETS = 360
private const val FILL_SAT = 0.35f
private const val FILL_VAL = 0.95f
private const val EDGE_SAT = 0.50f
private const val EDGE_VAL = 0.70f
internal const val MARQUEE_CHARS = 8
internal const val MARQUEE_MAX_DP = 200

/** 标签胶囊文字：填充是固定淡色（与主题无关），文字同样固定深色，两主题一致才可读。 */
internal val TAG_TEXT_COLOR: Int = ThemeColors.default(night = false).onSurface
internal const val MARQUEE_FOREVER = -1
