package com.workspace.proot

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class LauncherTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { t ->
            t.label = "快捷启动"
            t.subtitle = "打开快捷启动"
            t.state = Tile.STATE_INACTIVE
            t.updateTile()
        }
    }

    override fun onClick() {
        val sm = SettingsManager(getSharedPreferences("term-lou-settings", MODE_PRIVATE))
        val apps = sm.loadFavoriteApps()

        when {
            apps.isEmpty() -> {} // silent: nothing configured
            apps.size == 1 -> launchIntent(apps[0].pkg)?.let { startActivity(it) }
            else -> {
                if (Settings.canDrawOverlays(this)) {
                    TileDrawer(this, apps).show()
                } else {
                    runCatching {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    }
                }
            }
        }
    }

    private fun launchIntent(pkg: String): Intent? = runCatching {
        val base = packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        base
    }.getOrNull()
}
