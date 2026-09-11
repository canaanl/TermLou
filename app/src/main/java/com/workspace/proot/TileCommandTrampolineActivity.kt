package com.workspace.proot

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File

/**
 * 透明跳板：磁贴点击后启动 TermlouCommandRunner（前台服务），随后立即结束。
 * Android 12+ 不允许在后台直接拉起前台服务，先短暂持有一个前台 Activity 即合规。
 * 启动失败时静默重试一次，不再回退打开主界面（避免磁贴误触把 App 拉到前台）。
 */
class TileCommandTrampolineActivity : Activity() {

    private var startedService = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extraCmd = intent.getStringExtra(TermlouCommandRunner.EXTRA_COMMAND)?.takeIf { it.isNotBlank() }
        val extraId = intent.getStringExtra(TermlouCommandRunner.EXTRA_ID)
        val resolved: Pair<String, String?>? = if (extraCmd != null) {
            extraCmd to extraId
        } else {
            readPendingFile()?.let { f ->
                val text = runCatching { f.readText().trim().takeIf { it.isNotBlank() } }.getOrNull()
                text?.let { it to TermlouDirs.pendingId(f.name) }
            }
        }
        if (resolved != null) {
            if (!Settings.canDrawOverlays(this)) {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            } else {
                startCommandService(resolved.first, resolved.second)
            }
        }
        finish()
    }

    private fun startCommandService(cmd: String, cmdId: String?) {
        val tryStart = Runnable {
            if (startedService) return@Runnable
            runCatching {
                startedService = true
                startForegroundService(
                    Intent(this, TermlouCommandRunner::class.java)
                        .putExtra(TermlouCommandRunner.EXTRA_COMMAND, cmd)
                        .apply { cmdId?.let { putExtra(TermlouCommandRunner.EXTRA_ID, it) } }
                )
            }.onFailure {
                startedService = false
                // 冷启动窗口内 FGS 启动偶发被拒：短暂延迟后重试一次，仍失败则留给 pending 兜底。
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!startedService) {
                        runCatching {
                            startedService = true
                            startForegroundService(
                                Intent(this, TermlouCommandRunner::class.java)
                                    .putExtra(TermlouCommandRunner.EXTRA_COMMAND, cmd)
                                    .apply { cmdId?.let { putExtra(TermlouCommandRunner.EXTRA_ID, it) } }
                            )
                        }
                    }
                }, RETRY_DELAY_MS)
            }
        }
        tryStart.run()
    }

    /** 从 pending 文件读取上一条未能启动的命令文件（冷启动竞态时兜底）。 */
    private fun readPendingFile(): File? {
        val files = TermlouDirs.pendingFiles(this)
        if (files.isEmpty()) return null
        return files.last()
    }

    companion object {
        private const val RETRY_DELAY_MS = 150L
    }
}
