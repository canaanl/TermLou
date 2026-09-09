package com.workspace.proot

import android.app.AlertDialog
import android.app.usage.StorageStatsManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Process
import android.os.storage.StorageManager
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.animation.ValueAnimator
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class StorageDialog(
    private val ctx: Context,
    private val theme: ThemeColors,
    private val workspaceDir: File,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    private val excludeDirs = setOf("linux", "tmp")
    private val colorFile = theme.primary
    private val colorSys = UiTokens.amber

    private lateinit var chart: PieChartView
    private lateinit var fileLabel: TextView
    private lateinit var sysLabel: TextView
    private lateinit var totalLabel: TextView
    private lateinit var distroValue: TextView
    private lateinit var versionValue: TextView
    private lateinit var codenameValue: TextView
    private lateinit var archValue: TextView

    fun show() {
        val density = ctx.resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val contentView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }

        contentView.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.storage_title)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_TITLE
            typeface = Typeface.DEFAULT_BOLD
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
            textSize = UiTokens.TEXT_BODY
            setPadding(0, 20, 0, 4)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
        }
        legend.addView(fileLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        sysLabel = TextView(ctx).apply {
            setTextColor(colorSys)
            textSize = UiTokens.TEXT_BODY
            setPadding(0, 4, 0, 4)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
        }
        legend.addView(sysLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        totalLabel = TextView(ctx).apply {
            setTextColor(UiTokens.totalText)
            textSize = UiTokens.TEXT_BODY
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 12, 0, 0)
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

        val dialog = AlertDialog.Builder(ctx)
            .setView(contentView)
            .setPositiveButton(ctx.getString(R.string.close), null)
            .create()
        dialog.show()
        DialogStyler.apply(dialog, theme)
        loadData()
    }

    private fun addInfoRow(parent: LinearLayout, key: String, keyColor: Int, density: Float): TextView {
        parent.addView(TextView(ctx).apply {
            text = key
            setTextColor(keyColor)
            textSize = UiTokens.TEXT_META
            setPadding(0, (12 * density).toInt(), 0, 0)
        })
        val valueView = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 0)
        }
        parent.addView(valueView)
        return valueView
    }

    private fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
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
