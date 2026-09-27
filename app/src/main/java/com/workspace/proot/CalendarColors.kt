package com.workspace.proot

/**
 * 日历格子配色：「有笔记」的填充色分浅/夜两档写死，不随主题推导、不进 ThemeColors 槽位。
 * 待办三态与标签价签的线条色同样写死（在 vector drawable 里，运行时不 tint），
 * 这里同步一份常量供对比度单测锁定（CalendarColorsTest）。
 *
 * 对比度底线：日期数字 ≥7:1；12dp 图形 ≥2.5:1（图形靠「形状+颜色」双编码补偿）。
 */
object CalendarColors {

    /** 浅色主题：有笔记的格子填充（淡蓝灰），深色数字可读。 */
    const val NOTE_FILL_DAY = 0xFFE6EDF4.toInt()

    /** 夜间主题：有笔记的格子填充（深蓝灰），白色数字可读、红黄绿图标都拉得开。 */
    const val NOTE_FILL_NIGHT = 0xFF212C39.toInt()

    /** 下面四个与 drawable 里写死的线条色保持一致（改动要两边同步）。 */
    const val TODO_OPEN = 0xFFC0392B.toInt() // 全未完成 红 ○
    const val TODO_PARTIAL = 0xFFB58200.toInt() // 部分完成 黄 ◐
    const val TODO_DONE = 0xFF2D7D46.toInt() // 全部完成 绿 ✔
    const val TAG_GRAY = 0xFF888888.toInt() // 标签价签灰

    /** 当前主题下「有笔记」的填充色。 */
    fun noteFill(night: Boolean): Int = if (night) NOTE_FILL_NIGHT else NOTE_FILL_DAY
}
