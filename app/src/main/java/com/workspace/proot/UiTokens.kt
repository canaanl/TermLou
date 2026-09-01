package com.workspace.proot

/** 全 App 共享的 UI token：装饰色 + 字号档位。
 *
 *  颜色值均为历史硬编码字面量的逐位照抄，保证视觉零漂移；
 *  与 ThemeColors 语义色相等的项用相等测试锁定（见 UiTokensTest）。
 *
 *  注意：wheel 相关组件（WheelController / WheelAdapter）属白名单豁免，
 *  不引用本文件、不参与本文件的任何改动。
 */
object UiTokens {
    // ===== 字号档位 (sp) =====
    const val TEXT_TITLE = 18f
    const val TEXT_BODY = 15f
    const val TEXT_COMPACT = 13f
    const val TEXT_META = 12f

    // ===== 装饰色 (ARGB，逐位 = 原字面量) =====

    // 品牌绿 / 品牌蓝（值等于 ThemeColors.primary / tertiary，供无 theme 实例的 View 使用）
    val primaryGreen = 0xFF2D7D46.toInt()
    val tertiaryBlue = 0xFF0E639C.toInt()
    // 毛玻璃兜底底色（值等于 ThemeColors.surface）
    val surfaceFallback = 0xFF1E1E1E.toInt()

    // 状态栏 Matrix 绿
    val statusGreen = 0xFF00FF41.toInt()
    // 文件页「返回上级」链接色
    val linkCyan = 0xFF4DD0E1.toInt()
    // 文件页目录行半透明黄底
    val dirRowBg = 0x80FFD54F.toInt()
    // 存储饼图系统占用黄
    val amber = 0xFFFFD54F.toInt()
    // BraceMenu 花括号/芯片/操作标签
    val braceText = 0xF0FFFFFF.toInt()
    val chipSurface = 0x661E1E1E.toInt()
    val braceBlue = 0xFF64B5F6.toInt()
    val braceRed = 0xFFFF5252.toInt()
    val scrimStart = 0x99000000.toInt()
    // 白色半透明（进度条轨道 / TileDrawer 提亮条）
    val whiteFaint = 0x33FFFFFF.toInt()
    // Splash 状态文字
    val splashText = 0xFFA6A6A6.toInt()
    // Splash 背景光晕 / 字母辉光
    val backdropGlow = 0x1A0E639C.toInt()
    val letterGlow = 0x4D7FD8B0.toInt()
    // 文件页生命游戏细胞色
    val lifeCell = 0xB34CAF50.toInt()
    // AppPicker 搜索框底色
    val searchBg = 0x22FFFFFF.toInt()
    // 快捷命令合并「已确认」目标高亮
    val mergeOrange = 0xFFE67E22.toInt()
    // StorageDialog 总计文字
    val totalText = 0xFFCCCCCC.toInt()
    // TileDrawer 面板底色
    val tilePanelBg = 0xFFB820262E.toInt()
}
