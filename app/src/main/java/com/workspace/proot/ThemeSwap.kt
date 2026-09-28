package com.workspace.proot

import com.termux.terminal.TerminalSession

/**
 * 调色盘「保存即时换色」的跨重建中转：PaletteActivity 落盘后 mark()，
 * MainActivity.onResume 暂存活会话与当前 Tab 并 recreate()；旧实例 onDestroy
 * 见 hasRestore() 跳过杀会话，新实例 takeRestore() 回挂（不播开屏、不重启 shell，
 * 滚动缓冲与输入状态保留）。进程内静态：进程死亡即清，冷启动天然无残留。
 */
object ThemeSwap {

    /** 换色重建请求（PaletteActivity 落盘后置位，MainActivity.onResume 消费）。 */
    private var marked = false

    /** 待回挂的存活状态；null = 非换色重建（新实例走冷启动路径）。 */
    private var restore: Restore? = null

    /** 换色重建要回挂的存活状态：会话 + 用户离开前所在的 Tab。 */
    data class Restore(val session: TerminalSession, val tab: Int)

    /** 保存/恢复默认后调用：请求回主界面时换色重建。 */
    fun mark() {
        marked = true
    }

    /**
     * onResume 消费请求：true = 需要重建；存活会话与当前 Tab 已暂存
     * （会话尚未就绪时暂存为空，新实例按冷启动重建，配色同样即时生效）。
     */
    fun takeMarked(session: TerminalSession?, tab: Int): Boolean {
        if (!marked) return false
        marked = false
        restore = session?.let { Restore(it, tab) }
        return true
    }

    /** 旧实例 onDestroy 用：还有未回挂的存活会话则不杀（正处于换色重建）。 */
    fun hasRestore(): Boolean = restore != null

    /** 新实例 onCreate 用：取走并清空暂存（仅换色重建返回非 null）。 */
    fun takeRestore(): Restore? = restore.also { restore = null }
}
