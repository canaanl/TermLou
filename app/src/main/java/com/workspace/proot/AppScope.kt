package com.workspace.proot

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * 跨控制器共享的装配上下文：主题色、管理器、目录、偏好与主线程 Handler。
 * 由 MainActivity.onCreate 按顺序装配（loadThemeColors → prefs/dirs → managers），
 * 各 Controller 只读所需字段，不持有整个 Activity。
 */
class AppScope(
    val prefs: SharedPreferences,
    val mainHandler: Handler = Handler(Looper.getMainLooper()),
    val lxRoot: File,
    val wsFiles: File,
    val wsTmp: File
) {
    lateinit var theme: ThemeColors
    var cSurface = 0
    var cSurfaceVariant = 0
    var cPrimaryContainer = 0
    var cOutline = 0
    var cOnSurface = 0
    var cOnSurfaceVariant = 0
    var cPrimary = 0
    var cError = 0
    var cTertiary = 0

    lateinit var settingsManager: SettingsManager
    lateinit var terminalManager: TerminalManager
    lateinit var shortcutManager: ShortcutManager
    lateinit var fileListManager: FileListManager
    lateinit var uiBuilder: UiBuilder

    // 跨控制器瞬态会话状态（原 MainActivity 散装字段收归此处）
    var fromTile = false
    var pendingTileCommand: String? = null
}
