package com.workspace.proot

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 笔记磁贴：直起独立笔记页（singleTask 独立任务，不经 MainActivity，
 * 不唤醒终端/Linux 会话；独立页 excludeFromRecents，后台无残留）。
 */
class NotesTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { t ->
            t.label = getString(R.string.tile_notes_label)
            t.subtitle = getString(R.string.tile_notes_sub)
            t.state = Tile.STATE_INACTIVE
            t.updateTile()
        }
    }

    override fun onClick() {
        val target = Intent(this, NotesStandaloneActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, 0, target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { startActivityAndCollapse(pi) }
    }
}
