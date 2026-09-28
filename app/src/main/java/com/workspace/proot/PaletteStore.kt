package com.workspace.proot

import android.content.SharedPreferences

/**
 * 调色盘持久化：paletteOn 总开关 + 背景/主题绿两色。
 * 保存与恢复默认都 commit 后由 ThemeSwap 触发 MainActivity 换色重建（实时生效、
 * 会话与滚动缓冲保留）；未开启时 spec() 返回 null → 走昼夜两档默认主题，历史行为零漂移。
 */
object PaletteStore {

    private const val KEY_ON = "paletteOn"
    private const val KEY_BG = "paletteBg"
    private const val KEY_SEED = "paletteSeed"
    private const val KEY_NIGHT = "nightMode"

    /** 自定义配色；未开启或背景缺失（bg==0 不是合法不透明色）返回 null。 */
    fun spec(prefs: SharedPreferences): PaletteSpec? {
        if (!prefs.getBoolean(KEY_ON, false)) return null
        val bg = prefs.getInt(KEY_BG, 0)
        if (bg == 0) return null
        return PaletteSpec(bg, prefs.getInt(KEY_SEED, ThemeColors.SEED))
    }

    /** 当前生效主题：昼夜档 + 调色盘覆盖（夜间值直读落盘，模式同 MainActivity.loadThemeColors 的根因记录）。 */
    fun theme(prefs: SharedPreferences): ThemeColors =
        ThemeColors.default(prefs.getBoolean(KEY_NIGHT, true), spec(prefs))

    /** 保存调色盘（开 + 两色）：commit 同步落盘，调用方随后换色重建。 */
    fun save(prefs: SharedPreferences, background: Int, seed: Int) {
        prefs.edit()
            .putBoolean(KEY_ON, true)
            .putInt(KEY_BG, background)
            .putInt(KEY_SEED, seed)
            .commit()
    }

    /**
     * 恢复默认配色 = 原日间档（paletteOn=false 且 nightMode=false）：
     * 两键同一 commit 落盘；色值保留，便于再次微调时起步。
     */
    fun reset(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean(KEY_ON, false)
            .putBoolean(KEY_NIGHT, false)
            .commit()
    }
}
