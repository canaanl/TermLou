package com.workspace.proot

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.view.Gravity
import android.view.View
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
    private var onExportFolder: ((File) -> Unit)? = null
    private var onExportFile: ((File) -> Unit)? = null
    private var menuHost: FrameLayout? = null
    private var onDirectoryChanged: (() -> Unit)? = null
    private val braceMenu = BraceMenu(context)

    fun setCurrentDir(dir: File) {
        currentDir = dir
        onDirectoryChanged?.invoke()
    }
    fun getCurrentDir(): File = currentDir ?: File("")
    fun setCurrentTab(tab: Int) { currentTab = tab }
    /** 5.9.46：收可空 —— 状态栏可能还没建（lateinit，见 StatusController.statusViewOrNull）。 */
    fun setStatusText(textView: TextView?) { statusText = textView }
    fun setMainHandler(handler: Handler) { mainHandler = handler }
    fun setOnDirectoryChanged(listener: () -> Unit) { onDirectoryChanged = listener }

    /** 文件页根目录只到 `/workspace`；在那里按返回没有任何可去的上层。 */
    fun canNavigateUp(): Boolean {
        val dir = currentDir ?: return false
        return dir != workspaceRoot()
    }

    /**
     * 返回上一级。返回 false 表示已经在 `/workspace`（或目录尚未初始化），
     * 调用方据此在状态栏给出「没有上层目录」的提示，而不是静默无响应。
     */
    fun navigateUp(
        fileList: LinearLayout,
        navigateUp: () -> Unit = {},
        onFileClick: (File) -> Unit = {}
    ): Boolean {
        val parent = currentDir
            ?.takeIf { canNavigateUp() }
            ?.parentFile
            ?: return false
        currentDir = parent
        onDirectoryChanged?.invoke()
        refreshFileList(fileList, navigateUp, onFileClick)
        StatusMarquee.show(statusText, parent.name, StatusMarquee.BASE_MS, mainHandler) {
            restoreStatusPath()
        }
        return true
    }

    private fun workspaceRoot(): File = File(context.filesDir, "workspace")

    /**
     * 回到常态路径显示。
     *
     * 5.9.47：状态栏拆两块后，本函数只管**块②**（内容区）。
     * 特征字 `Files` 在块①，独立维护、永不滚动 —— 所以这里不再传前缀。
     *
     * 放得下：纯文字静止显示。放不下：横向循环滚出来看（见 [StatusMarquee.showPersistent]）。
     */
    private fun restoreStatusPath() {
        if (currentTab != 2) return
        val tv = statusText ?: return
        val p = getRelativePath()
        if (p.isEmpty()) {
            StatusMarquee.clearPersistent(tv)
            tv.text = ""
            return
        }
        StatusMarquee.showPersistent(tv, p, mainHandler)
    }
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

        val files = dir.listFiles()?.filter {
            !it.name.startsWith(".") &&
                !(it.isDirectory && (it.name == "linux" || it.name == "tmp"))
        }?.sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: emptyList()

        if (files.isEmpty()) {
            fileList.addView(TextView(context).apply {
                text = context.getString(R.string.file_empty)
                setTextColor(theme.onSurfaceVariant)
                textSize = nameTextSize
                setPaddingMd(UiTokens.SPACE_4, UiTokens.SPACE_5, UiTokens.SPACE_4, UiTokens.SPACE_5)
            })
            return
        }

        for (f in files) {
            val isDir = f.isDirectory
            val fullName = f.name
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingMd(UiTokens.SPACE_4, UiTokens.SPACE_3, UiTokens.SPACE_4, UiTokens.SPACE_3)
                setBackgroundColor(if (isDir) UiTokens.dirRowBg else Color.TRANSPARENT)
            }

            if (isDir) {
                row.addView(TextView(context).apply {
                    text = fullName
                    setTextColor(theme.onSurface)
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
                        setCurrentDir(f)
                        refreshFileList(fileList, navigateUp, onFileClick)
                        StatusMarquee.show(statusText, fullName, StatusMarquee.BASE_MS, mainHandler) {
                            restoreStatusPath()
                        }
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
                    setTextColor(theme.onSurface)
                    textSize = nameTextSize
                    typeface = Typeface.MONOSPACE
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (extPart.isNotEmpty()) {
                    nameContainer.addView(TextView(context).apply {
                        text = extPart
                        setTextColor(theme.onSurface)
                        textSize = nameTextSize
                        typeface = Typeface.MONOSPACE
                        maxLines = 1
                        setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_1, UiTokens.SPACE_0)
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
                    setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_2, UiTokens.SPACE_0)
                })
                row.addView(fileInfo)

                row.setOnClickListener {
                    row.animate().cancel()
                    row.animate().alpha(0.5f).setDuration(60).withEndAction {
                        row.animate().alpha(1f).setDuration(120).start()
                    }.start()
                    StatusMarquee.show(statusText, fullName, StatusMarquee.BASE_MS, mainHandler) {
                        restoreStatusPath()
                    }
                    showBraceMenu(row, f, false, fileList, navigateUp, onFileClick)
                }
            }

            fileList.addView(row)
            fileList.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(theme.outline)
            })
        }
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
            host, ay, rh, context.getString(R.string.file_export), context.getString(R.string.file_delete), needsConfirm,
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
