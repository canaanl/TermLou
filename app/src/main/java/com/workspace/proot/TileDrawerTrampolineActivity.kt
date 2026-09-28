package com.workspace.proot

import android.app.Activity
import android.os.Bundle

/**
 * 透明跳板（启动器磁贴多收藏抽屉）：纯"收面板触发器"——磁贴 startActivityAndCollapse
 * 拉起本跳板触达系统收面板，onCreate 同步立即结束，与命令跳板（TileCommandTrampolineActivity）
 * 逐行同构。
 *
 * 5.5.5 闪现根因：展示抽屉的 250ms 定时器此前寄生在本任务里，任务"活满"250ms 会让系统
 * 完整播放打开/关闭转场动画（真机所见"闪现一下、缩回去"的可见面）；"启动即结束"使系统
 * 取消该转场——命令磁贴同主题同启动方式不闪，正是此机制（已真机对照验证）。
 * 抽屉展示改由 LauncherTileService 侧 Handler 延时执行，与本任务生命周期完全解耦；
 * 本类不再承载任何展示逻辑，防补发双弹层也不再需要令牌（定时器全服务侧只挂一次）。
 */
class TileDrawerTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
