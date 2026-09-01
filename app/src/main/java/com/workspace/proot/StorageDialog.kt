package com.workspace.proot

import android.app.Activity
import android.app.Dialog
import android.app.usage.StorageStatsManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Process
import android.os.storage.StorageManager
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
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
) : Dialog(ctx, R.style.FullScreenDialog) {

    private val excludeDirs = setOf("linux", "tmp")
    private val colorFile = theme.primary
    private val colorSys = UiTokens.amber

    private lateinit var chart: PieChartView
    private lateinit var fileLabel: TextView
    private lateinit var sysLabel: TextView
    private lateinit var totalLabel: TextView
    private lateinit var contentView: LinearLayout
    private lateinit var card: FrameLayout

    init {
        window?.apply {
            setLayout(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)
        }

        val density = ctx.resources.displayMetrics.density
        val pad = (40 * density).toInt()
        val cardW = ctx.resources.displayMetrics.widthPixels - (16 * density).toInt()
        val cardH = (ctx.resources.displayMetrics.heightPixels * 0.76f).toInt()

        val root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { dismissWithFade() }
        }

        card = FrameLayout(ctx).apply {
            setOnClickListener { dismissWithFade() }
        }

        contentView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }

        chart = PieChartView(ctx, theme)
        chart.layoutParams = LinearLayout.LayoutParams(
            (230 * density).toInt(),
            (230 * density).toInt()
        ).apply { topMargin = (20 * density).toInt() }
        contentView.addView(chart)

        contentView.addView(View(ctx), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

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

        contentView.addView(legend, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (20 * density).toInt() })
        contentView.addView(View(ctx), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        card.addView(contentView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        root.addView(card, FrameLayout.LayoutParams(cardW, cardH, Gravity.CENTER))

        setContentView(root)
    }

    override fun show() {
        super.show()
        card.post {
            if (card.width > 0 && card.height > 0) {
                val act = ctx as? Activity
                if (act != null) applyFrostedCard(card, act)
                else card.setBackgroundColor(theme.surface)
                card.alpha = 0f
                card.animate().alpha(1f).setDuration(300).start()
                loadData()
            }
        }
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
                withContext(Dispatchers.Main) {
                    chart.setData(fileBytes, sysBytes)
                    chart.startSweep()

                    val fStr = "\u25A0  文件占用  ${formatBytes(fileBytes)}"
                    fileLabel.text = SpannableString(fStr).apply {
                        setSpan(ForegroundColorSpan(colorFile), 0, 1, 0)
                    }
                    val sStr = "\u25A0  系统占用  ${formatBytes(sysBytes)}"
                    sysLabel.text = SpannableString(sStr).apply {
                        setSpan(ForegroundColorSpan(colorSys), 0, 1, 0)
                    }
                    totalLabel.text = "总计  ${formatBytes(total)}"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    totalLabel.text = "统计失败: ${e.message}"
                }
            }
        }
    }

    private fun dismissWithFade() {
        card.animate().alpha(0f).setDuration(200).withEndAction {
            dismiss()
        }.start()
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
    private var fileBytes = 0L
    private var sysBytes = 0L
    private var sweepAngle = 0f
    private var fileSweep = 0f
    private var sysSweep = 0f
    private var started = false

    private val colorFile = theme.primary
    private val colorSys = UiTokens.amber

    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
        maskFilter = android.graphics.BlurMaskFilter(30f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    fun setData(file: Long, sys: Long) {
        fileBytes = file
        sysBytes = sys
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
                val total = fileBytes + sysBytes
                if (total > 0) {
                    val ratio = fileBytes.toFloat() / total
                    fileSweep = sweepAngle * ratio
                    sysSweep = sweepAngle - fileSweep
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val total = fileBytes + sysBytes
        if (total == 0L || sweepAngle <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) * 0.82f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        val shadowRect = RectF(rect.left - 12, rect.top - 6, rect.right + 12, rect.bottom + 6)

        canvas.drawArc(shadowRect, -90f, sweepAngle, true, shadowPaint)

        mainPaint.color = colorFile
        canvas.drawArc(rect, -90f, fileSweep, true, mainPaint)

        mainPaint.color = colorSys
        canvas.drawArc(rect, -90f + fileSweep, sysSweep, true, mainPaint)
    }
}
