package com.workspace.proot

import android.content.Intent
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * 纾佽创/鍒嗕韩鍏ュ彛鍩燂細tile 鍛戒护 intent銆佸揩鎹峰惎鍔ㄧ鐞嗛〉銆佸伐鍧婂叆鍙ｃ€佸揩鎹峰惎鍔ㄥ垵濮嬪寲纭銆? * 鍘?MainActivity 鐩稿叧 ~120 琛屾敹褰掓澶勩€? */
class OverlayCommandsController(
    private val activity: MainActivity,
    private val scope: AppScope,
    private val status: StatusController
) {
    private var needRefreshShortcuts = false
    private var cachedApps: List<AppEntry>? = null
    private var quickInitArmed = false
    private var quickInitArmedGen = -1

    /** 纾佽创鍛戒护鍏ュ彛锛氫細璇濆氨缁垯鐩村啓锛屽惁鍒欏瓨 pending 绛?startShell 鍥炲～銆?*/
    fun handleNewIntent(
        intent: Intent,
        session: TerminalSession?,
        showTerminal: () -> Unit,
        writeTile: (String) -> Unit
    ) {
        scope.fromTile = intent.getStringExtra("tile_command") != null
        if (scope.fromTile) {
            val cmd = intent.getStringExtra("tile_command")
            if (cmd != null && cmd.isNotBlank() && session != null) {
                showTerminal()
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    delay(200)
                    writeTile(cmd)
                }
            } else {
                scope.pendingTileCommand = cmd
            }
        }
    }

    fun openShortcutSettings() {
        needRefreshShortcuts = true
        activity.startActivity(Intent(activity, ShortcutSettingsActivity::class.java))
    }

    fun consumeRefreshFlag(): Boolean {
        if (!needRefreshShortcuts) return false
        needRefreshShortcuts = false
        return true
    }

    fun showAppPicker() {
        AppPickerDialog(
            activity, scope.cPrimary, scope.cOnSurfaceVariant,
            loadAppCache(), scope.settingsManager, scope.theme
        ).show()
    }

    fun loadAppCache(): List<AppEntry> {
        cachedApps?.let { return it }
        val pm = activity.packageManager
        val list = runCatching { pm.getInstalledApplications(0) }
            .getOrElse { emptyList() }
            .filter { it.enabled }
            .mapNotNull { ai ->
                runCatching {
                    val pkg = ai.packageName
                    if (pm.getLaunchIntentForPackage(pkg) == null) null
                    else AppEntry(
                        pkg,
                        pm.getApplicationLabel(ai).toString(),
                        null,
                        (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
        cachedApps = list
        return list
    }

    fun onQuickInitClick() {
        if (quickInitArmed && status.gen() == quickInitArmedGen) {
            scope.settingsManager.clearFavoriteApps()
            quickInitArmed = false
            status.showTempStatus(activity.getString(R.string.sc_cleared_all))
        } else {
            quickInitArmed = true
            status.showTempStatus(activity.getString(R.string.sc_init_confirm))
            quickInitArmedGen = status.gen()
        }
    }

    fun openDialogMaker() {
        activity.startActivity(Intent(activity, DialogMakerActivity::class.java))
    }

    fun openSplashMaker() {
        activity.startActivity(Intent(activity, SplashMakerActivity::class.java))
    }
}
