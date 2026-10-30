package com.workspace.proot

import android.content.Context
import android.widget.TextView

/**
 * 文件页右下角的返回 FAB。
 *
 * 与笔记页 FAB 共用 [NeuFab] 的几何与拟态材质，只把符号换成「←」。
 * **可点性语义**：始终保留点击监听 —— 根目录时点按不导航，而是在状态栏提示
 * 「已在顶层，没有上层目录」；否则才执行返回上一级。这样禁用态也有反馈，
 * 不会出现「点了完全没反应」的死键。
 */
class FileBackFab(
    context: Context,
    private val theme: ThemeColors,
    private val onNavigateUp: () -> Unit,
    private val onBlocked: () -> Unit
) : TextView(context) {

    var canGoUp: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            renderState()
        }

    init {
        NeuFab.style(
            this,
            theme,
            GLYPH,
            context.getString(R.string.file_back),
        ) {
            if (canGoUp) onNavigateUp() else onBlocked()
        }
        renderState()
    }

    private fun renderState() {
        setTextColor(if (canGoUp) theme.primaryVisible else NeuFab.disabledIconColor(theme))
        contentDescription = context.getString(
            if (canGoUp) R.string.file_back else R.string.file_no_parent
        )
    }

    private companion object {
        const val GLYPH = "←"
    }
}
