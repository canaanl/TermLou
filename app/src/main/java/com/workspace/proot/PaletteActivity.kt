package com.workspace.proot

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** 色号输入合法字符（输入期间原样显示，失焦归一为大写）。 */
private const val HEX_CHARS = "0123456789abcdefABCDEF"

/**
 * 调色盘工坊：手动可设仅「背景色 + 主题色」两项，SV 取色方块 + 色相条实时取色，
 * 当前色支持色号直接输入（满 6 位实时联动取色器，3 位简写失焦展开）；
 * 其余槽位由背景按对比度规则自动计算，只读展示；预览卡用候选色实时合成验证可读性。
 * 保存/恢复默认均 commit 后置 ThemeSwap 并 finish：回主界面即刻换色重建，
 * 不播开屏、终端会话与滚动缓冲保留。
 */
class PaletteActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var night = true
    private val base: ThemeColors by lazy { PaletteStore.theme(prefs) }

    /** 当前编辑目标：false=背景色，true=主题色。 */
    private var editingSeed = false
    private var bg = 0
    private var seed = 0
    private val hsv = FloatArray(3)

    private lateinit var targetBgChip: TextView
    private lateinit var targetSeedChip: TextView
    private lateinit var svSquare: SvSquareView
    private lateinit var hueStrip: HueStripView
    private lateinit var swatchBg: GradientDrawable
    private lateinit var hexText: EditText
    private lateinit var previewCard: LinearLayout
    private lateinit var previewTitle: TextView
    private lateinit var previewBody: TextView
    private lateinit var previewStripBg: GradientDrawable
    private lateinit var previewStripText: TextView
    private lateinit var previewBtnBg: GradientDrawable
    private lateinit var previewBtnText: TextView
    private lateinit var derived: ThemeColors

    private class ChipViews(val swatch: View, val hex: TextView, val pick: (ThemeColors) -> Int)
    private val chips = mutableListOf<ChipViews>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        night = prefs.getBoolean("nightMode", true)
        val stored = PaletteStore.spec(prefs)
        bg = stored?.background ?: ThemeColors.default(night).surface
        seed = stored?.seed ?: ThemeColors.SEED
        Color.colorToHSV(bg, hsv)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(base.surface)
        }
        root.addView(buildTitleBar())
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        scroll.addView(form)
        root.addView(scroll)
        setContentView(root)

        buildForm(form)
        paintTargetChips()
        renderAll()
    }

    private fun buildTitleBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(base.surfaceVariant)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(TextView(this@PaletteActivity).apply {
            text = "←"
            setTextColor(base.onSurface)
            textSize = UiTokens.TEXT_TITLE
            setPadding(0, 0, dp(14), 0)
            setOnClickListener { finish() }
        })
        addView(TextView(this@PaletteActivity).apply {
            text = getString(R.string.palette_title)
            setTextColor(base.onSurface)
            textSize = UiTokens.TEXT_TITLE
            typeface = Typeface.DEFAULT_BOLD
        })
    }

    private fun buildForm(form: LinearLayout) {
        // ===== 编辑目标切换 =====
        val targetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        targetBgChip = targetChip(getString(R.string.palette_target_bg))
        targetSeedChip = targetChip(getString(R.string.palette_target_seed))
        targetBgChip.setOnClickListener { selectTarget(seedTarget = false) }
        targetSeedChip.setOnClickListener { selectTarget(seedTarget = true) }
        targetRow.addView(targetBgChip)
        targetRow.addView(targetSeedChip)
        form.addView(targetRow)

        // ===== 效果预览（候选背景 + 候选主题色实时合成） =====
        form.addView(sectionLabel(getString(R.string.palette_preview)))
        previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        previewTitle = TextView(this).apply {
            text = "标题 Aa · 字体与图标"
            textSize = UiTokens.TEXT_BODY
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        }
        previewBody = TextView(this).apply {
            text = "次要文字 Aa · 描述与辅助信息"
            textSize = UiTokens.TEXT_COMPACT
            setPadding(0, 0, 0, dp(10))
        }
        previewStripText = TextView(this).apply {
            text = "卡片 · 输入框 Aa"
            textSize = UiTokens.TEXT_COMPACT
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        previewStripBg = GradientDrawable().apply { cornerRadius = dpF(10) }
        previewStripText.background = previewStripBg
        previewBtnText = TextView(this).apply {
            text = getString(R.string.save)
            textSize = UiTokens.TEXT_COMPACT
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        previewBtnBg = GradientDrawable().apply { cornerRadius = dpF(14) }
        previewBtnText.background = previewBtnBg
        val btnWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            addView(previewBtnText)
        }
        previewCard.addView(previewTitle)
        previewCard.addView(previewBody)
        previewCard.addView(previewStripText)
        previewCard.addView(btnWrap)
        form.addView(previewCard)

        // ===== 取色：SV 方块 + 色相条 =====
        form.addView(sectionLabel(getString(R.string.palette_pick)))
        svSquare = SvSquareView(this) { s, v ->
            hsv[1] = s
            hsv[2] = v
            commitHsv()
        }
        hueStrip = HueStripView(this) { h ->
            hsv[0] = h
            commitHsv()
        }
        form.addView(svSquare, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(200)
        ))
        val hueParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(26)
        ).apply { topMargin = dp(8) }
        form.addView(hueStrip, hueParams)

        // 当前色行：色块 + 色号直接输入（# 为固定前缀，满 6 位实时联动上方取色器）
        val currentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val swatch = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
        swatchBg = GradientDrawable().apply { cornerRadius = dpF(10) }
        swatch.background = swatchBg
        hexText = EditText(this).apply {
            textSize = 17f
            typeface = Typeface.MONOSPACE
            setTextColor(base.onSurface)
            background = null
            isSingleLine = true
            setPadding(0, 0, dp(6), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val raw = s?.toString() ?: return
                    val cleaned = raw.filter { it in HEX_CHARS }.take(6)
                    if (cleaned != raw) {
                        val sel = selectionStart
                        s.replace(0, s.length, cleaned)
                        setSelection(sel.coerceAtMost(cleaned.length))
                        return // 替换已同步递归进本 watcher，6 位时已应用
                    }
                    if (cleaned.length == 6) applyHex(cleaned)
                }
            })
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) normalizeHex() }
        }
        currentRow.addView(swatch)
        currentRow.addView(TextView(this).apply {
            text = "#"
            textSize = 17f
            typeface = Typeface.MONOSPACE
            setTextColor(base.onSurface)
            setPadding(dp(12), 0, 0, 0)
        })
        currentRow.addView(hexText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        currentRow.addView(TextView(this).apply {
            text = getString(R.string.palette_current)
            textSize = UiTokens.TEXT_COMPACT
            setTextColor(base.onSurfaceVariant)
        })
        form.addView(currentRow)

        // ===== 自动派生色（只读） =====
        form.addView(sectionLabel(getString(R.string.palette_derived)))
        addChip(form, R.string.palette_chip_font) { it.onSurface }
        addChip(form, R.string.palette_chip_card) { it.surfaceVariant }
        addChip(form, R.string.palette_chip_outline) { it.outline }
        addChip(form, R.string.palette_chip_sec_text) { it.onSurfaceVariant }
        addChip(form, R.string.palette_chip_secondary) { it.secondary }
        addChip(form, R.string.palette_chip_tertiary) { it.tertiary }
        addChip(form, R.string.palette_chip_error) { it.error }
        addChip(form, R.string.palette_chip_term_bg) { it.surface }
        addChip(form, R.string.palette_chip_term_fg) { it.onSurface }

        // ===== 恢复默认 / 保存 =====
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        }
        val resetBtn = Button(this).apply {
            text = getString(R.string.palette_reset)
            setTextColor(base.primaryVisible)
            textSize = UiTokens.TEXT_BODY
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(5)
            }
            ButtonStyle.outlined(this, base.primaryVisible)
            setOnClickListener {
                PaletteStore.reset(prefs)
                ThemeSwap.mark()
                Toast.makeText(this@PaletteActivity, getString(R.string.palette_reset_done), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        val saveBtn = Button(this).apply {
            text = getString(R.string.palette_save)
            setTextColor(ColorMath.inkOf(base.primary))
            textSize = UiTokens.TEXT_BODY
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            ButtonStyle.apply(this, base.primary, base.primaryVisible)
            setOnClickListener {
                PaletteStore.save(prefs, bg, seed)
                ThemeSwap.mark()
                Toast.makeText(this@PaletteActivity, getString(R.string.palette_saved), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        btnRow.addView(resetBtn)
        btnRow.addView(saveBtn)
        form.addView(btnRow)
    }

    // ===== 组件构造 =====

    private fun targetChip(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = UiTokens.TEXT_BODY
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(9), dp(16), dp(9))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        }
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(base.onSurface)
        textSize = UiTokens.TEXT_BODY
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(12), 0, dp(6))
    }

    private fun addChip(parent: LinearLayout, labelRes: Int, pick: (ThemeColors) -> Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val swatch = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)) }
        swatch.background = GradientDrawable().apply { cornerRadius = dpF(7) }
        val label = TextView(this).apply {
            text = getString(labelRes)
            setTextColor(base.onSurface)
            textSize = UiTokens.TEXT_BODY
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        }
        val hex = TextView(this).apply {
            setTextColor(base.onSurfaceVariant)
            textSize = UiTokens.TEXT_COMPACT
            typeface = Typeface.MONOSPACE
        }
        row.addView(swatch)
        row.addView(label)
        row.addView(hex)
        parent.addView(row)
        chips.add(ChipViews(swatch, hex, pick))
    }

    // ===== 状态与渲染 =====

    private fun selectTarget(seedTarget: Boolean) {
        editingSeed = seedTarget
        Color.colorToHSV(if (editingSeed) seed else bg, hsv)
        // 目标切换是显式动作：无视焦点强制刷新色号输入框
        val digits = ColorMath.hex(if (editingSeed) seed else bg).removePrefix("#")
        if (hexText.text.toString() != digits) {
            hexText.setText(digits)
            hexText.setSelection(digits.length)
        }
        paintTargetChips()
        renderAll()
    }

    private fun paintTargetChips() {
        fun style(chip: TextView, selected: Boolean) {
            val d = GradientDrawable().apply {
                cornerRadius = dpF(16)
                if (selected) {
                    setColor(base.primaryContainer)
                    setStroke(dp(1).coerceAtLeast(1), base.primaryVisible)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1).coerceAtLeast(1), base.outline)
                }
            }
            chip.background = d
            chip.setTextColor(if (selected) base.primaryVisible else base.onSurfaceVariant)
        }
        style(targetBgChip, !editingSeed)
        style(targetSeedChip, editingSeed)
    }

    /** 取色器写入 hsv 后统一提交：只写当前目标，不做 HSV 往返（保住灰阶下的色相）。 */
    private fun commitHsv() {
        val c = Color.HSVToColor(hsv)
        if (editingSeed) seed = c else bg = c
        renderAll()
    }

    /**
     * 色号输入满 6 位：精确 RGB 直写当前目标（不经 HSV 往返，色号逐位准确），
     * hsv 数组仅同步取色器标记位置——SV 方块白圈与色相条竖线随之跳到对应位置。
     */
    private fun applyHex(hex6: String) {
        val c = Color.parseColor("#$hex6")
        if (c == (if (editingSeed) seed else bg)) return // 程序化回写/重复触发
        if (editingSeed) seed = c else bg = c
        Color.colorToHSV(c, hsv)
        renderAll()
    }

    /** 失焦归一化：3 位简写展开成 6 位并应用；6 位转大写；不满/非法回滚为当前色号。 */
    private fun normalizeHex() {
        val digits = hexText.text.toString().filter { it in HEX_CHARS }
        val expanded = if (digits.length == 3) digits.map { it.toString().repeat(2) }.joinToString("") else null
        if (expanded != null) applyHex(expanded)
        val canonical = ColorMath.hex(if (editingSeed) seed else bg).removePrefix("#")
        if (hexText.text.toString() != canonical) {
            hexText.setText(canonical)
            hexText.setSelection(canonical.length)
        }
    }

    private fun renderAll() {
        val cur = if (editingSeed) seed else bg
        swatchBg.setColor(cur)
        // 聚焦输入时不回写（用户正在打字），失焦后由 normalizeHex 归一
        if (!hexText.hasFocus()) {
            val digits = ColorMath.hex(cur).removePrefix("#")
            if (hexText.text.toString() != digits) {
                hexText.setText(digits)
                hexText.setSelection(digits.length)
            }
        }
        svSquare.setHsv(hsv[0], hsv[1], hsv[2])
        hueStrip.setHue(hsv[0])
        renderPreview()
    }

    /** 预览卡与派生色块全部由候选色现算（与真正生效走同一函数）。 */
    private fun renderPreview() {
        derived = ThemeColors.default(night, PaletteSpec(bg, seed))
        previewCard.setBackgroundColor(derived.surface)
        previewTitle.setTextColor(derived.onSurface)
        previewBody.setTextColor(derived.onSurfaceVariant)
        previewStripBg.setColor(derived.surfaceVariant)
        previewStripText.setTextColor(derived.onSurface)
        previewBtnBg.setColor(derived.primary)
        // 预览按钮与真按钮同构：填充=品牌原色，描边=可见变体（此处演示的就是最终生效规则）
        previewBtnBg.setStroke(dp(1).coerceAtLeast(1), derived.primaryVisible)
        previewBtnText.setTextColor(derived.onPrimary)
        for (chip in chips) {
            val c = chip.pick(derived)
            (chip.swatch.background as GradientDrawable).setColor(c)
            chip.hex.text = ColorMath.hex(c)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun dpF(v: Int): Float = v * resources.displayMetrics.density

    // ===== 取色组件 =====

    /** SV 取色方块：横轴饱和、纵轴明度，底色随色相；触点回传 (saturation, value)。 */
    private class SvSquareView(context: Context, private val onPick: (Float, Float) -> Unit) : View(context) {
        var hue = 0f
            private set
        var sat = 1f
            private set
        var value = 1f
            private set

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var whiteShader: Shader? = null
        private var blackShader: Shader? = null
        private var shaderW = 0
        private var shaderH = 0

        fun setHsv(h: Float, s: Float, v: Float) {
            hue = h
            sat = s
            value = v
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            if (whiteShader == null || shaderW != width || shaderH != height) {
                whiteShader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
                blackShader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0x00000000, Color.BLACK, Shader.TileMode.CLAMP)
                shaderW = width
                shaderH = height
            }
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = whiteShader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = blackShader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            val cx = sat * width
            val cy = (1f - value) * height
            val d = resources.displayMetrics.density
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * d
            paint.color = Color.BLACK
            canvas.drawCircle(cx, cy, 12f * d, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, 12f * d - 2f * d, paint)
            paint.style = Paint.Style.FILL
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (width <= 0 || height <= 0) return super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        // 手势起点即锁住父级拦截：框内拖动专注取色，不让 ScrollView 抢去做页面滚动
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    sat = (event.x / width).coerceIn(0f, 1f)
                    value = (1f - event.y / height).coerceIn(0f, 1f)
                    onPick(sat, value)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    /** 色相条：全色环渐变，触点回传 0..360。 */
    private class HueStripView(context: Context, private val onPick: (Float) -> Unit) : View(context) {
        var hue = 0f
            private set

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shader: Shader? = null
        private var shaderW = 0

        fun setHue(h: Float) {
            hue = h
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            if (shader == null || shaderW != width) {
                shader = LinearGradient(
                    0f, 0f, width.toFloat(), 0f,
                    intArrayOf(
                        Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                        Color.BLUE, Color.MAGENTA, Color.RED
                    ), null, Shader.TileMode.CLAMP
                )
                shaderW = width
            }
            paint.style = Paint.Style.FILL
            paint.shader = shader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            val x = (hue / 360f) * width
            val d = resources.displayMetrics.density
            paint.strokeWidth = 4f * d
            paint.color = Color.BLACK
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            paint.strokeWidth = 2f * d
            paint.color = Color.WHITE
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (width <= 0) return super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        // 同取色框：手势起点锁住父级拦截，色相条内拖动不触发页面滚动
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    onPick(((event.x / width) * 360f).coerceIn(0f, 360f))
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    companion object {
        private const val PREFS_NAME = "term-lou-settings"
    }
}
