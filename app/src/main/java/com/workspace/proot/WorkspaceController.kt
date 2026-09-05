package com.workspace.proot

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 鏂囦欢鍩燂細鏂囦欢 Tab 鏁村潡 UI銆佸埛鏂般€佸鍏?瀵煎嚭/鍒嗕韩銆丄CTION_SEND 钀界洏銆? * 鍘?MainActivity 鏂囦欢鐩稿叧 ~200 琛屾敹褰掓澶勩€? */
class WorkspaceController(
    private val activity: MainActivity,
    private val scope: AppScope,
    private val status: StatusController,
    private val pickFiles: () -> Unit,
    private val pickFolder: () -> Unit
) {
    private lateinit var filesHeader: TextView
    private lateinit var fileList: LinearLayout
    private var filesListRoot: FrameLayout? = null

    /** 鏂囦欢 Tab 鏁村潡 UI锛堝惈搴曟爮锛夛紝鎸傚埌 filesArea 涓嬨€?*/
    fun buildInto(filesArea: LinearLayout) {
        filesHeader = TextView(activity).apply {
            text = "  /workspace/"
            setTextColor(scope.cOnSurfaceVariant)
            textSize = UiTokens.TEXT_COMPACT
            setPadding(16, 4, 16, 4)
            setBackgroundColor(scope.cSurfaceVariant)
            visibility = View.GONE
        }
        fileList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val filesScroll = TabSwipeScrollView(
            activity,
            onSwipeRight = { if (activity.currentTab == 1 && !activity.isSetupVisible()) activity.showTerminalView() },
            onSwipeLeft = { if (activity.currentTab == 1 && !activity.isSetupVisible()) activity.showTab(2) }
        )
        filesScroll.addView(fileList)

        filesListRoot = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply {
                weight = 1f
            }
            addView(filesScroll, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        filesArea.addView(filesHeader)
        filesArea.addView(filesListRoot)

        filesArea.addView(scope.uiBuilder.createFileBottomBar(
            onImportClick = { pickFiles() },
            onImportFolderClick = { pickFolder() }
        ))
    }

    private fun isSetupVisible(): Boolean = activity.isSetupVisible()

    fun getRelativePath(): String {
        val base = scope.wsFiles.absolutePath
        val cur = scope.fileListManager.getCurrentDir().absolutePath
        return if (cur.isEmpty() || cur == "/" || cur == base) "" else cur.removePrefix(base).replace('\\', '/')
    }

    fun refreshFileList() {
        if (scope.fileListManager.getCurrentDir().path.isEmpty()) {
            scope.fileListManager.setCurrentDir(scope.wsFiles)
        }
        scope.fileListManager.setCurrentTab(activity.currentTab)
        scope.fileListManager.setStatusText(status.statusView)
        scope.fileListManager.setMainHandler(scope.mainHandler)
        filesListRoot?.let { scope.fileListManager.setMenuHost(it) }
        scope.fileListManager.setOnExportFolder { shareFolder(it) }
        scope.fileListManager.setOnExportFile { shareFile(it) }
        scope.fileListManager.refreshFileList(fileList, { /* navigateUp callback */ }) { /* onFileClick callback */ }
    }

    fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val name = getFileName(uri) ?: "shared_${System.currentTimeMillis()}"
                val dir = scope.wsFiles
                dir.mkdirs()
                val dest = resolveUniqueFile(dir, name)
                val input = activity.contentResolver.openInputStream(uri)
                if (input == null) {
                    withContext(Dispatchers.Main) {
                        status.snack(activity.getString(R.string.share_read_fail))
                    }
                    return@launch
                }
                input.use { src -> dest.outputStream().use { dst -> src.copyTo(dst) } }
                withContext(Dispatchers.Main) {
                    refreshFileList()
                    status.snack(activity.getString(R.string.share_saved))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.snack(activity.getString(R.string.save_failed))
                }
            }
        }
    }

    private fun resolveUniqueFile(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var file = File(dir, name)
        var counter = 1
        while (file.exists()) {
            file = File(dir, "$base($counter)$ext")
            counter++
        }
        return file
    }

    fun importFiles(uris: List<Uri>) {
        for (uri in uris) {
            importFile(uri)
        }
    }

    private fun importFile(uri: Uri) {
        activity.lifecycleScope.launch {
            try {
                val fileName = getFileName(uri) ?: "imported_file"
                withContext(Dispatchers.IO) {
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        val target = File(scope.fileListManager.getCurrentDir(), fileName)
                        target.outputStream().use { input.copyTo(it) }
                    }
                }
                refreshFileList()
            } catch (e: Exception) {
                status.setStatusText(activity.getString(R.string.import_failed_fmt, e.message.toString()))
            }
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val mime = getMimeType(file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(shareIntent, activity.getString(R.string.share_to)))
        } catch (e: Exception) {
            status.setStatusText(activity.getString(R.string.share_failed_fmt, e.message.toString()))
        }
    }

    private fun getMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "txt" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "md" -> "text/markdown"
            "csv" -> "text/csv"
            "py" -> "text/x-python"
            "sh" -> "application/x-sh"
            else -> "application/octet-stream"
        }
    }

    private fun shareFolder(folder: File) {
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) { status.setStatusText(activity.getString(R.string.pack_progress)) }
                val zipFile = File(scope.wsTmp, "${folder.name}.zip")
                withContext(Dispatchers.IO) {
                    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                        folder.walkTopDown().forEach { file ->
                            if (file.isFile) {
                                val entryName = folder.parentFile?.let { file.toRelativeString(it) } ?: file.name
                                zos.putNextEntry(ZipEntry(entryName))
                                file.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    shareFile(zipFile)
                    status.setStatusText(activity.getString(R.string.pack_done))
                }
            } catch (e: Exception) {
                status.setStatusText(activity.getString(R.string.pack_failed_fmt, e.message.toString()))
            }
        }
    }

    fun importFolder(uri: Uri) {
        activity.lifecycleScope.launch {
            try {
                status.setStatusText(activity.getString(R.string.import_progress))
                val rootDoc = DocumentFile.fromTreeUri(activity, uri)
                    ?: throw Exception(activity.getString(R.string.import_unreadable))
                withContext(Dispatchers.IO) {
                    val folderName = rootDoc.name ?: "imported"
                    val targetDir = File(scope.fileListManager.getCurrentDir(), folderName)
                    targetDir.mkdirs()
                    syncDocuments(rootDoc, targetDir)
                }
                refreshFileList()
                status.setStatusText(activity.getString(R.string.import_done))
            } catch (e: Exception) {
                status.setStatusText(activity.getString(R.string.import_failed_fmt, e.message.toString()))
            }
        }
    }

    private fun syncDocuments(doc: DocumentFile, targetDir: File) {
        for (child in doc.listFiles()) {
            if (child.isDirectory) {
                val subDir = File(targetDir, child.name ?: "unknown")
                subDir.mkdirs()
                syncDocuments(child, subDir)
            } else if (child.isFile) {
                val name = child.name ?: "unknown"
                child.uri?.let { uri ->
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        File(targetDir, name).outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String? = FileUtils.getFileName(activity, uri)
}
