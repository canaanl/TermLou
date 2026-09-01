package com.workspace.proot

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class FileListManager(
    private val context: Context,
    private val theme: ThemeColors
) {
    private val nameTextSize = UiTokens.TEXT_TITLE
    private val infoTextSize = UiTokens.TEXT_BODY
    private var currentDir: File? = null
    private var currentTab: Int = 0
    private var statusText: TextView? = null
    private var mainHandler: Handler? = null
    private var statusTextRestoreRunnable: Runnable? = null
    private var onExportFolder: ((File) -> Unit)? = null
    private var onExportFile: ((File) -> Unit)? = null
    private var menuHost: FrameLayout? = null
    private val braceMenu = BraceMenu(context)

    fun setCurrentDir(dir: File) { currentDir = dir }
    fun getCurrentDir(): File = currentDir ?: File("")

    fun setCurrentTab(tab: Int) { currentTab = tab }
    fun setStatusText(textView: TextView) { statusText = textView }
    fun setMainHandler(handler: Handler) { mainHandler = handler }
    fun setStatusTextRestoreRunnable(runnable: Runnable?) { statusTextRestoreRunnable = runnable }
    fun setOnExportFolder(listener: (File) -> Unit) { onExportFolder = listener }
    fun setOnExportFile(listener: (File) -> Unit) { onExportFile = listener }
    fun setMenuHost(host: FrameLayout) { menuHost = host }

    fun refreshFileList(
        fileList: LinearLayout,
        navigateUp: () -> Unit,
        onFileClick: (File) -> Unit
    ) {
        val dir = currentDir ?: return
        fileList.removeAllViews()

        fun staggerIn() {
            val d = context.resources.displayMetrics.density
            for (i in 0 until fileList.childCount) {
                val child = fileList.getChildAt(i)
                child.alpha = 0f
                child.translationY = 18 * d
                child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setStartDelay((minOf(i, 8) * 30).toLong())
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        if (dir != File(context.filesDir, "workspace")) {
            val upRow = TextView(context).apply {
                text = "↩  返回上级"
                setTextColor(UiTokens.linkCyan)
                textSize = nameTextSize
                typeface = Typeface.MONOSPACE
                setPadding(16, 14, 16, 14)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    dir.parentFile?.let { newDir ->
                        currentDir = newDir
                        refreshFileList(fileList, navigateUp, onFileClick)
                        statusText?.text = newDir.name
                        statusTextRestoreRunnable?.let { mainHandler?.removeCallbacks(it) }
                        val r = Runnable {
                            if (currentTab == 1) {
                                val p = getRelativePath()
                                statusText?.text = if (p.isEmpty()) "Files" else "Files | $p"
                            }
                        }
                        statusTextRestoreRunnable = r
                        mainHandler?.postDelayed(r, 1000)
                    }
                }
            }
            fileList.addView(upRow)
            fileList.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(theme.outline)
            })
        }

        val files = dir.listFiles()?.filter {
            !it.name.startsWith(".") &&
                !(it.isDirectory && (it.name == "linux" || it.name == "tmp"))
        }?.sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: emptyList()

        if (files.isEmpty()) {
            fileList.addView(TextView(context).apply {
                text = "    （空目录）"
                setTextColor(theme.onSurfaceVariant)
                textSize = nameTextSize
                setPadding(16, 24, 16, 24)
            })
            staggerIn()
            return
        }

        for (f in files) {
            val isDir = f.isDirectory
            val fullName = f.name
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 10, 16, 10)
                setBackgroundColor(if (isDir) UiTokens.dirRowBg else Color.TRANSPARENT)
            }

            if (isDir) {
                row.addView(TextView(context).apply {
                    text = fullName
                    setTextColor(Color.WHITE)
                    textSize = nameTextSize
                    typeface = Typeface.MONOSPACE
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.setOnClickListener {
                    row.animate().cancel()
                    row.animate().alpha(0.5f).setDuration(60).withEndAction {
                        row.animate().alpha(1f).setDuration(120).start()
                    }.start()
                    row.postDelayed({
                        currentDir = f
                        refreshFileList(fileList, navigateUp, onFileClick)
                        statusText?.text = fullName
                        statusTextRestoreRunnable?.let { mainHandler?.removeCallbacks(it) }
                        val r = Runnable {
                            if (currentTab == 1) {
                                val p = getRelativePath()
                                statusText?.text = if (p.isEmpty()) "Files" else "Files | $p"
                            }
                        }
                        statusTextRestoreRunnable = r
                        mainHandler?.postDelayed(r, 1000)
                    }, 140)
                }
                row.setOnLongClickListener {
                    showBraceMenu(row, f, true, fileList, navigateUp, onFileClick)
                    true
                }
            } else {
                val dotIdx = fullName.lastIndexOf('.')
                val (basePart, extPart) = if (dotIdx <= 0) Pair(fullName, "")
                                          else Pair(fullName.substring(0, dotIdx), fullName.substring(dotIdx))
                val nameContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                nameContainer.addView(TextView(context).apply {
                    text = basePart
                    setTextColor(Color.WHITE)
                    textSize = nameTextSize
                    typeface = Typeface.MONOSPACE
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (extPart.isNotEmpty()) {
                    nameContainer.addView(TextView(context).apply {
                        text = extPart
                        setTextColor(Color.WHITE)
                        textSize = nameTextSize
                        typeface = Typeface.MONOSPACE
                        maxLines = 1
                        setPadding(0, 0, 4, 0)
                    })
                }

                val fileInfo = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                fileInfo.addView(nameContainer)
                fileInfo.addView(TextView(context).apply {
                    text = formatSize(f.length())
                    setTextColor(theme.onSurfaceVariant)
                    textSize = infoTextSize
                    setPadding(0, 0, 8, 0)
                })
                row.addView(fileInfo)

                row.setOnClickListener {
                    row.animate().cancel()
                    row.animate().alpha(0.5f).setDuration(60).withEndAction {
                        row.animate().alpha(1f).setDuration(120).start()
                    }.start()
                    statusText?.text = fullName
                    statusTextRestoreRunnable?.let { mainHandler?.removeCallbacks(it) }
                    val r = Runnable {
                        if (currentTab == 1) {
                            val p = getRelativePath()
                            statusText?.text = if (p.isEmpty()) "Files" else "Files | $p"
                        }
                    }
                    statusTextRestoreRunnable = r
                    mainHandler?.postDelayed(r, 1000)
                    showBraceMenu(row, f, false, fileList, navigateUp, onFileClick)
                }
            }

            fileList.addView(row)
            fileList.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(theme.outline)
            })
        }
        staggerIn()
    }

    private fun showBraceMenu(
        row: View,
        f: File,
        isDir: Boolean,
        fileList: LinearLayout,
        navigateUp: () -> Unit,
        onFileClick: (File) -> Unit
    ) {
        val host = menuHost ?: return
        val rowLoc = IntArray(2)
        val hostLoc = IntArray(2)
        row.getLocationInWindow(rowLoc)
        host.getLocationInWindow(hostLoc)
        val ay = (rowLoc[1] - hostLoc[1]).toFloat()
        val rh = row.height.toFloat().coerceAtLeast(1f)
        val needsConfirm = isDir && f.listFiles()?.isNotEmpty() == true
        braceMenu.show(
            host, ay, rh, "导出", "删除", needsConfirm,
            onOpt1 = {
                if (isDir) onExportFolder?.invoke(f) else onExportFile?.invoke(f)
            },
            onOpt2 = {
                if (isDir) f.deleteRecursively() else f.delete()
                refreshFileList(fileList, navigateUp, onFileClick)
            }
        )
    }

    private fun getRelativePath(): String {
        val dir = currentDir ?: return ""
        val base = File(context.filesDir, "workspace")
        val cur = dir.absolutePath
        val b = base.absolutePath
        return if (cur == b) "" else cur.removePrefix(b).replace('\\', '/')
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> "%.1fMB".format(bytes.toDouble() / (1024 * 1024))
        }
    }
}
