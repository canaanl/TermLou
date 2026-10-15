package com.workspace.proot

import android.app.usage.StorageStatsManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Process
import android.os.storage.StorageManager
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.animation.ValueAnimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 系统信息卡片：发行版/版本/代号/架构 + 文件/系统占用饼图 + 图例。
 * 原 StorageDialog 的内容区解耦为可嵌入视图（终端介绍页复用），Dialog 外壳不再使用。
 * 每次打开介绍页创建新实例，饼图 sweep 动画自然重播；关闭时取消加载任务。
 */
class SystemInfoCardView(
    private val ctx: Context,
    private val theme: ThemeColors,
    private val workspaceDir: File,
) {
    private val excludeDirs = setOf("linux", "tmp")
    private val colorFile = theme.primaryVisible
    private val colorSys = UiTokens.amber

    private lateinit var chart: PieChartView
    private lateinit var fileLabel: TextView
    private lateinit var sysLabel: TextView
    private lateinit var totalLabel: TextView
    private lateinit var distroValue: TextView
    private lateinit var versionValue: TextView
    private lateinit var codenameValue: TextView
    private lateinit var archValue: TextView
    private lateinit var refreshValue: TextView
    private lateinit var gameModeValue: TextView

    private var loadJob: Job? = null

    val view: LinearLayout = build()

    private fun build(): LinearLayout {
        val density = ctx.resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val contentView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // 5.9.40：原 pad = 24dp 裸值，改走 MD3 间距档（SPACE_5）
            setPaddingMd(UiTokens.SPACE_5, UiTokens.SPACE_5, UiTokens.SPACE_5, UiTokens.SPACE_5)
        }

        contentView.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.storage_title)
            setTextColor(theme.onSurface)
            // 5.9.40：字号走规范档并补行高（bold 由 textMd 一并设置）
            textMd(UiTokens.TEXT_TITLE, UiTokens.LINE_TITLE, bold = true)
        })

        // 双列：左系统信息 / 右饼图+图例
        val keyColor = theme.onSurfaceVariant
        val columns = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val leftCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        distroValue = addInfoRow(leftCol, ctx.getString(R.string.sysinfo_distro), keyColor, density)
        versionValue = addInfoRow(leftCol, ctx.getString(R.string.sysinfo_version), keyColor, density)
        codenameValue = addInfoRow(leftCol, ctx.getString(R.string.sysinfo_codename), keyColor, density)
        archValue = addInfoRow(leftCol, ctx.getString(R.string.sysinfo_arch), keyColor, density)
        // 5.9.58：实测刷新率。用来当场判断"高刷请求生效没有"，不必再翻系统设置。
        refreshValue = addInfoRow(leftCol, ctx.getString(R.string.sysinfo_refresh), keyColor, density)
        // 5.9.59：游戏模式读数，用来判断 android:isGame 有没有被系统认下。
        gameModeValue = addInfoRow(leftCol, ctx.getString(R.string.sysinfo_gamemode), keyColor, density)
        columns.addView(leftCol, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        val rightCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        chart = PieChartView(ctx, theme)
        chart.layoutParams = LinearLayout.LayoutParams(
            (140 * density).toInt(),
            (140 * density).toInt()
        ).apply { topMargin = (20 * density).toInt() }
        rightCol.addView(chart)

        val legend = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        fileLabel = TextView(ctx).apply {
            setTextColor(colorFile)
            // 5.9.40：原 20dp 上调到 MD3 的 24dp（SPACE_5）；字号补行高
            textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY, bold = true)
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_5, UiTokens.SPACE_0, UiTokens.SPACE_1)
            gravity = Gravity.START
        }
        legend.addView(fileLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        sysLabel = TextView(ctx).apply {
            setTextColor(colorSys)
            textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY, bold = true)
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_1, UiTokens.SPACE_0, UiTokens.SPACE_1)
            gravity = Gravity.START
        }
        legend.addView(sysLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        totalLabel = TextView(ctx).apply {
            setTextColor(if (theme.night) UiTokens.totalText else theme.onSurface)
            textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY, bold = true)
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_3, UiTokens.SPACE_0, UiTokens.SPACE_0)
            gravity = Gravity.START
        }
        legend.addView(totalLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        rightCol.addView(legend, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * density).toInt() })
        columns.addView(rightCol, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        contentView.addView(columns, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * density).toInt() })
        return contentView
    }

    private fun addInfoRow(parent: LinearLayout, key: String, keyColor: Int, density: Float): TextView {
        parent.addView(TextView(ctx).apply {
            text = key
            setTextColor(keyColor)
            textMd(UiTokens.TEXT_META, UiTokens.LINE_META)
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_3, UiTokens.SPACE_0, UiTokens.SPACE_0)
            // 5.9.56：此处原有 5.9.40 加的键名色块（roundedBg + CORNER_XS），按用户要求删除。
            //
            // 它当初是为了"左右两列扫视时靠色块区分键与值"加的，但用户实机看着是
            // "多余的方框"：系统信息这一整块外面已经有大方框，里面再套四个小框属于
            // 画蛇添足。键与值本来就能靠字号+颜色+粗细分开（键 12sp onSurfaceVariant，
            // 值 15sp 粗体 onSurface），色块并非必需。
            //
            // 只删背景这一项：字号、内距、颜色、文案、卡片高度一律不变。
        })
        val valueView = TextView(ctx).apply {
            setTextColor(theme.onSurface)
            textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY, bold = true)
            setPaddingMdAll(UiTokens.SPACE_0)
        }
        parent.addView(valueView)
        return valueView
    }

    /**
     * 刷新率读数（5.9.58）。
     *
     * 显示**实测**值，不是我们请求的值 —— 两者的差就是"系统有没有理我们"。
     * 低于 [DisplayRefresh.requestedHz] 时用带「请求…未生效」的那条，一眼看出被驳回
     * （vivo/一加/小米的按 app 高刷白名单会这样）。拿不到就显示"未知"，不猜。
     */
    private fun refreshText(): String {
        val dm = runCatching { ctx.getSystemService(DisplayManager::class.java) }.getOrNull()
        val hz = runCatching { dm?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate }
            .getOrNull()
            ?.takeIf { it > 0f } ?: return ctx.getString(R.string.sysinfo_unknown)
        val want = DisplayRefresh.requestedHz
        return if (want != null && hz < want) {
            ctx.getString(R.string.sysinfo_refresh_fmt_low, hz, want)
        } else {
            ctx.getString(R.string.sysinfo_refresh_fmt, hz)
        }
    }

    /**
     * 游戏模式读数（5.9.59）—— 纯诊断，**不参与任何决策**。
     *
     * `GameManager.getGameMode()` 是 API 31+ 公开方法，文档明确：不是游戏的应用
     * 一律返回 `GAME_MODE_UNSUPPORTED`。所以显示"不支持"就等于告诉我们
     * `android:isGame="true"` 没被系统认下 —— 这时高刷被卡在厂商白名单上，
     * 唯一出路是去系统设置里把 TermLou 手动加进游戏/高性能列表，再重开应用。
     *
     * 拿不到就显示"未知"，不猜。
     */
    private fun gameModeText(): String {
        val mode = DisplayRefresh.gameMode ?: return ctx.getString(R.string.sysinfo_unknown)
        val name = when (mode) {
            DisplayRefresh.GAME_MODE_UNSUPPORTED -> R.string.sysinfo_gamemode_unsupported
            DisplayRefresh.GAME_MODE_STANDARD -> R.string.sysinfo_gamemode_standard
            DisplayRefresh.GAME_MODE_PERFORMANCE -> R.string.sysinfo_gamemode_performance
            DisplayRefresh.GAME_MODE_BATTERY -> R.string.sysinfo_gamemode_battery
            else -> R.string.sysinfo_gamemode_custom
        }
        return ctx.getString(R.string.sysinfo_gamemode_fmt, ctx.getString(name), mode)
    }

    /** 异步加载占用与发行版数据；数据到达后 setData + startSweep（饼图进入动画）。 */
    fun load(scope: CoroutineScope): Job {
        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.IO) {
            try {
                val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
                val stats = ssm.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT, ctx.packageName, Process.myUserHandle()
                )
                val total = stats.appBytes + stats.dataBytes
                val fileBytes = dirSizeExcluding(workspaceDir, excludeDirs)
                val sysBytes = (total - fileBytes).coerceAtLeast(0L)
                val distro = resolveDistroInfo(File(workspaceDir, "linux"))
                val abi = runCatching { deviceAbi() }.getOrDefault("unknown")
                withContext(Dispatchers.Main) {
                    chart.setData(listOf(fileBytes to colorFile, sysBytes to colorSys))
                    chart.startSweep()
                    val unknown = ctx.getString(R.string.sysinfo_unknown)
                    distroValue.text = distro?.pretty?.ifBlank { unknown } ?: unknown
                    versionValue.text = distro?.versionId?.ifBlank { unknown } ?: unknown
                    codenameValue.text = distro?.codename?.ifBlank { unknown } ?: unknown
                    archValue.text = abi
                    refreshValue.text = refreshText()
                    gameModeValue.text = gameModeText()

                    val fStr = ctx.getString(R.string.storage_file_fmt, formatBytes(fileBytes))
                    fileLabel.text = SpannableString(fStr).apply {
                        setSpan(ForegroundColorSpan(colorFile), 0, 1, 0)
                    }
                    val sStr = ctx.getString(R.string.storage_sys_fmt, formatBytes(sysBytes))
                    sysLabel.text = SpannableString(sStr).apply {
                        setSpan(ForegroundColorSpan(colorSys), 0, 1, 0)
                    }
                    totalLabel.text = ctx.getString(R.string.storage_total_fmt, formatBytes(total))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    totalLabel.text = ctx.getString(R.string.storage_failed_fmt, e.message.toString())
                }
            }
        }
        return loadJob!!
    }

    fun cancel() {
        loadJob?.cancel()
        loadJob = null
    }

    private fun dirSizeExcluding(dir: File, excludeNames: Set<String>): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .onEnter { p -> p.name !in excludeNames }
            .onFail { _, _ -> true }
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "%.1fMB".format(bytes.toDouble() / (1024 * 1024))
            else -> "%.2fGB".format(bytes.toDouble() / (1024 * 1024 * 1024))
        }
    }
}

class PieChartView(context: Context, private val theme: ThemeColors) : View(context) {
    private var items: List<Pair<Long, Int>> = emptyList()
    private var sweepAngle = 0f
    private var started = false

    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
        maskFilter = android.graphics.BlurMaskFilter(30f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    /**
     * 数据更新（实时场景直接调用即重绘，不重新滚动动画）。
     * 每片 = (字节, 颜色)，值为 0 的片跳过。
     */
    fun setData(data: List<Pair<Long, Int>>) {
        items = data
        if (!started) sweepAngle = 360f
        invalidate()
    }

    fun startSweep() {
        if (started) return
        started = true
        sweepAngle = 0f
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                sweepAngle = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val total = items.sumOf { it.first }
        if (total <= 0L || sweepAngle <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) * 0.82f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        val shadowRect = RectF(rect.left - 12, rect.top - 6, rect.right + 12, rect.bottom + 6)

        canvas.drawArc(shadowRect, -90f, sweepAngle, true, shadowPaint)

        var start = -90f
        for ((value, color) in items) {
            if (value <= 0L) continue
            val sweep = sweepAngle * (value.toFloat() / total)
            mainPaint.color = color
            canvas.drawArc(rect, start, sweep, true, mainPaint)
            start += sweep
        }
    }
}

/** 单条横向堆叠占比条：值按比例分色分段，实时场景直接 setData 即重绘。 */
class StackedBarView(context: Context) : View(context) {
    private var items: List<Pair<Long, Int>> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setData(data: List<Pair<Long, Int>>) {
        items = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val total = items.sumOf { it.first }
        if (total <= 0L) return
        val barHeight = (10 * resources.displayMetrics.density).toInt().toFloat()
        val rect = RectF(0f, height / 2f - barHeight / 2f, width.toFloat(), height / 2f + barHeight / 2f)
        var x = 0f
        for ((value, color) in items) {
            if (value <= 0L) continue
            val w = value.toFloat() / total * rect.width()
            paint.color = color
            canvas.drawRect(x, rect.top, x + w, rect.bottom, paint)
            x += w
        }
    }
}
