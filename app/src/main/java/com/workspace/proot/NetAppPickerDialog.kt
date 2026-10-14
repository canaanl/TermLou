package com.workspace.proot

import android.app.Activity

/**
 * 抓包（网络代理）应用选择弹窗（5.9.51 重构：主体已并入 [AppPickerDialogBase]，此处只留差异）。
 *
 * 语义：**只改内存、确定时才落盘** —— 点行只动 `selected` 集合，
 * 真正写 SharedPreferences 是点"确定"时（`onDone` 里 `saveCaptureApps` + 回调数量）。
 * 这与收藏夹版 [AppPickerDialog] 的"勾选即落盘"相反，故刻意不覆写 `onTogglePersist`。
 *
 * 构造函数签名与重构前**逐字一致**（`onChanged` 仍在 `sm` 与 `theme` 之间），
 * 调用方 `NetworkController` 无需改动。
 */
class NetAppPickerDialog(
    ctx: Activity,
    accent: Int,
    onSurfaceVariant: Int,
    all: List<AppEntry>,
    sm: SettingsManager,
    private val onChanged: (Int) -> Unit,
    theme: ThemeColors
) : AppPickerDialogBase(
    ctx, accent, onSurfaceVariant, all, sm, theme,
    R.string.picker_capture_title,
    // 原来这行在构造时就求值，时机与收藏夹版相同。
    sm.loadCaptureApps().toMutableSet()
) {
    override fun onDone(selected: Set<String>) {
        sm.saveCaptureApps(selected)
        onChanged(selected.size)
    }
}