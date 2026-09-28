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
                    showDrawer(apps)
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
     * 多收藏抽屉（5.5.5 时序与闪现根治）：
     * ① startActivityAndCollapse 拉起跳板收面板（TileService 无 collapsePanels，唯一公开 API），
     *    跳板 onCreate 同步立即结束——任务"启动即结束"使系统取消其打开/关闭转场动画，
     *    这是"闪现/缩回"的可见面来源（命令磁贴不闪即此机制，真机已对照验证）；
     * ② 250ms 展示定时器挂在服务侧主循环（不寄生在跳板任务里）：等面板收完动画再弹抽屉，
     *    Handler 在 main looper，服务随后 onStopListening/onDestroy 不影响执行，
     *    抽屉用 applicationContext 添加浮窗——时序仍为「面板收回 → 抽屉弹出」；
     * ③ 发送失败（startActivityAndCollapse 抛异常）则不挂展示定时器：收面板与弹窗都不发生；
     * ④ 进程冷启动窗口内 Activity 启动可能被系统静默丢弃：150ms 后补发一次触发器
     *    （只重复跳板，展示定时器仅挂一次——补发天然不会双弹层，无需任何令牌）。
     */
    private fun showDrawer(apps: List<FavoriteApp>) {
        val intent = Intent(this, TileDrawerTrampolineActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        val pi = PendingIntent.getActivity(
            this, DRAWER_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sent = runCatching { startActivityAndCollapse(pi); true }.getOrElse { false }
        if (!sent) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (Settings.canDrawOverlays(applicationContext)) {
                runCatching { TileDrawer(applicationContext, apps).show() }
            }
        }, COLLAPSE_DELAY_MS)
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

        /** 等快速设置面板收起动画走完再弹抽屉（面板收起约 250~300ms，真机不顺可微调）。 */
        private const val COLLAPSE_DELAY_MS = 250L
    }
}
