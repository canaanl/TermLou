package com.workspace.proot

import android.content.Context
import java.io.File

/**
 * termlou 内部 IPC 目录的统一入口。
 * 从 workspace 迁出到 app 私有 files 根，避免污染用户可见的工作区。
 * 物理路径: filesDir/.termlou（proot 内通过 -b filesDir/.termlou:/termlou 映射为 /termlou）
 */
object TermlouDirs {

    fun base(context: Context): File = File(context.filesDir, ".termlou")

    fun req(context: Context): File = File(base(context), "req")

    fun res(context: Context): File = File(base(context), "res")

    fun clipboardReq(context: Context): File = File(base(context), "clipboard/req")

    fun clipboardRes(context: Context): File = File(base(context), "clipboard/res")

    fun pending(context: Context): File =
        File(base(context), CommandTileService.PENDING_FILE)

    /** 首次升级迁移：把旧 workspace/.termlou 里仍有价值的数据搬到新位置。 */
    fun migrateFromWorkspace(context: Context, wsFiles: File) {
        runCatching {
            val old = File(wsFiles, ".termlou")
            if (!old.isDirectory) return
            val newBase = base(context)
            newBase.mkdirs()
            // splash.json：启动工坊点阵，唯一需要保住的旧数据
            val oldSplash = File(old, "splash.json")
            val newSplash = File(newBase, "splash.json")
            if (oldSplash.isFile && !newSplash.exists()) {
                oldSplash.copyTo(newSplash, overwrite = false)
            }
        }
    }
}
