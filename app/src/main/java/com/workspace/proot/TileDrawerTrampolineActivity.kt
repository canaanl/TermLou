package com.workspace.proot

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * 透明跳板（启动器磁贴多收藏抽屉）：磁贴先 startActivityAndCollapse 收起快速设置面板，
 * 本跳板等面板收完动画（250ms）再弹抽屉并结束自己——时序 =「面板收回 → 抽屉弹出」。
 *
 * 窗口零可见契约由 Theme.TermLou.Transparent 保证（windowDisablePreview 禁预览窗出生 +
 * 栏色透明）：跳板存活期每一帧 0 像素，闪现根治不依赖"活得快"；
 * 展示定时器经 consumeShowArmed 一次性令牌，补发拉起的第二个跳板不重复挂载；
 * 抽屉用 applicationContext 添加浮窗，与本 Activity 生命周期解耦。
 */
class TileDrawerTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apps = TileDrawerCache.apps.ifEmpty {
            SettingsManager(getSharedPreferences("term-lou-settings", MODE_PRIVATE)).loadFavoriteApps()
        }
        if (apps.size > 1 && Settings.canDrawOverlays(this) && TileDrawerCache.consumeShowArmed()) {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { TileDrawer(applicationContext, apps).show() }
                finish()
            }, COLLAPSE_DELAY_MS)
        } else {
            finish()
        }
    }

    companion object {
        /** 等快速设置面板收起动画走完再弹抽屉（面板收起约 250~300ms，真机不顺可微调）。 */
        private const val COLLAPSE_DELAY_MS = 250L
    }
}
