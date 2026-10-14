package com.workspace.proot

import android.app.Activity

/**
 * 收藏夹应用选择弹窗（5.9.51 重构：主体已并入 [AppPickerDialogBase]，此处只留差异）。
 *
 * 语义：**勾选即落盘** —— 点一下行就立刻写 SharedPreferences，
 * 所以关掉弹窗不点"确定"也已经是保存过的。
 * （抓包版 [NetAppPickerDialog] 相反，只改内存、确定时才存。）
 *
 * 构造函数签名与重构前**逐字一致**，调用方 `OverlayCommandsController.showAppPicker` 无需改动。
 */
class AppPickerDialog(
    ctx: Activity,
    accent: Int,
    onSurfaceVariant: Int,
    all: List<AppEntry>,
    sm: SettingsManager,
    theme: ThemeColors
) : AppPickerDialogBase(
    ctx, accent, onSurfaceVariant, all, sm, theme,
    R.string.pick_app,
    // 原来这行在构造时就求值（`private val selected = sm.loadFavoriteApps()...`），
    // 现在在子类构造实参里求值 —— 时机完全相同，仍是同一个调用栈内。
    sm.loadFavoriteApps().associateBy { it.pkg }.keys.toMutableSet()
) {
    // 分支与原来那 4 行 if/else 逐支对应：nowSelected=true 即刚勾上（原 else 分支），
    // false 即刚取消（原 if 分支）。刻意不压成一行，便于逐行比对。
    override fun onTogglePersist(pkg: String, label: String, nowSelected: Boolean) {
        if (nowSelected) {
            sm.addFavoriteApp(label, pkg)
        } else {
            sm.removeFavoriteApp(pkg)
        }
    }
}