package com.workspace.proot

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.io.File

class CommandTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val prefs = getSharedPreferences("term-lou-settings", MODE_PRIVATE)
        val cmd = prefs.getString("tileCommand", "") ?: ""
        qsTile?.let { tile ->
            tile.label = "磁贴命令"
            tile.subtitle = if (cmd.isNotBlank()) cmd.take(20) else "未配置命令"
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        val prefs = getSharedPreferences("term-lou-settings", MODE_PRIVATE)
        val raw = prefs.getString("tileCommand", "") ?: ""
        val cmd = normalize(raw)
        if (cmd.isBlank()) {
            return
        }

        // rootfs 就绪 → 一律走无界面浮窗路径（命令是否含 termlou-ui 无关）
        val rootfsReady = File(filesDir, "workspace/linux/etc/passwd").exists()
        val target: Intent = if (rootfsReady) {
            Intent(this, TileCommandTrampolineActivity::class.java)
                .putExtra(TermlouCommandRunner.EXTRA_COMMAND, cmd)
                // App 在后台时让跳板跑进独立 Task，避免把主界面一起带到前台
                .apply {
                    if (!isAppForeground()) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    }
                }
        } else {
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(TermlouCommandRunner.EXTRA_COMMAND, cmd)
            }
        }
        val pi = PendingIntent.getActivity(this, 0, target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // 先落盘再启动：即使本次启动被冷启动竞态吞掉，命令也不丢失，
        // TermlouCommandRunner / MainActivity 会在下次消费 pending 文件。
        persistPendingCommand(cmd)

        // 进程刚冷起时首次点击的 Activity 启动可能被系统静默丢弃，延迟重试一次补齐。
        // 仅在磁贴仍处于监听期（面板未收起）时补发，并用 runCatching 兜底，绝不让重试导致崩溃。
        val coldStart = SystemClock.elapsedRealtime() - TermLouApp.appColdStartAt < COLD_START_WINDOW_MS
        startActivityAndCollapse(pi)
        if (coldStart) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (qsTile != null) {
                    runCatching { startActivityAndCollapse(pi) }
                }
            }, RETRY_DELAY_MS)
        }
    }

    /** 命令落盘（tmp+rename 原子写），供启动被丢时兜底消费。 */
    private fun persistPendingCommand(cmd: String) {
        runCatching {
            val dir = File(filesDir, ".termlou").apply { mkdirs() }
            val out = File(dir, PENDING_FILE)
            val tmp = File(dir, "$PENDING_FILE.tmp")
            tmp.writeText(cmd)
            tmp.renameTo(out)
        }
    }

    /** 模板简写 @名 → termlou-ui @名，保证无界面 bash -c 能执行。 */
    private fun normalize(cmd: String): String {
        val t = cmd.trim()
        return if (t.startsWith("@")) "termlou-ui $t" else t
    }

    /** App 主界面是否处于前台（决定跳板是否需进独立 Task，避免后台点击把 App 拉起）。 */
    private fun isAppForeground(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        runCatching { ActivityManager.getMyMemoryState(state) }
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    companion object {
        const val PENDING_FILE = "tile_pending.json"
        private const val COLD_START_WINDOW_MS = 3000L
        private const val RETRY_DELAY_MS = 150L
    }
}
