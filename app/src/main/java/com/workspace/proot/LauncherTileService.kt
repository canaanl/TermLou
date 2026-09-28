package com.workspace.proot

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class LauncherTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { t ->
            t.label = getString(R.string.tile_launch_label)
            t.subtitle = getString(R.string.tile_launch_sub)
            t.state = Tile.STATE_INACTIVE
            t.updateTile()
        }
        // 预取：面板展开时就把收藏与图标解码好（进程级缓存，跳板弹出时零 binder/零解码）
        runCatching { TileDrawerCache.prefetch(this) }
    }

    override fun onClick() {
        val sm = SettingsManager(getSharedPreferences("term-lou-settings", MODE_PRIVATE))
        val apps = TileDrawerCache.apps.ifEmpty { sm.loadFavoriteApps() }

        when {
            apps.isEmpty() -> {} // silent: nothing configured
            apps.size == 1 -> launchIntent(apps[0].pkg)?.let { intent ->
                // 统一 startActivityAndCollapse：先收快速设置面板再启动（笔记磁贴同款）
                val pi = PendingIntent.getActivity(
                    this, SINGLE_REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                runCatching { startActivityAndCollapse(pi) }
            }
            else -> {
                if (Settings.canDrawOverlays(this)) {
                    showDrawer()
                } else {
                    runCatching {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val pi = PendingIntent.getActivity(
                            this, SINGLE_REQUEST_CODE, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        startActivityAndCollapse(pi)
                    }
                }
            }
        }
    }

    /**
     * 多收藏抽屉：先 startActivityAndCollapse 收起快速设置面板（TileService 无 collapsePanels，
     * 这是唯一公开的收面板 API），透明跳板等面板收完动画（250ms）再弹抽屉——
     * 点磁贴时序 = 面板收回 → 抽屉弹出。
     * 进程冷启动窗口内 Activity 启动可能被系统静默丢弃：150ms 后补发一次（命令磁贴同款）；
     * 抽屉侧有进程级单例闸，重复触发不会弹两层。
     */
    private fun showDrawer() {
        val intent = Intent(this, TileDrawerTrampolineActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        val pi = PendingIntent.getActivity(
            this, DRAWER_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { startActivityAndCollapse(pi) }
        val coldStart = SystemClock.elapsedRealtime() - TermLouApp.appColdStartAt < COLD_START_WINDOW_MS
        if (coldStart) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (qsTile != null) {
                    runCatching { startActivityAndCollapse(pi) }
                }
            }, RETRY_DELAY_MS)
        }
    }

    private fun launchIntent(pkg: String): Intent? = runCatching {
        val base = packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        base
    }.getOrNull()

    companion object {
        private const val DRAWER_REQUEST_CODE = 1
        private const val SINGLE_REQUEST_CODE = 0
        private const val COLD_START_WINDOW_MS = 3000L
        private const val RETRY_DELAY_MS = 150L
    }
}
