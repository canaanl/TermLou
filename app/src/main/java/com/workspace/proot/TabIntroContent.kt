package com.workspace.proot

/** Tab 介绍页数据：纯静态文案（中英双版走资源），动态系统信息只在终端页插入。 */
data class TabIntroSection(val titleRes: Int, val bodyRes: Int)

data class TabIntroPage(
    val iconRes: Int,
    val titleRes: Int,
    val leadRes: Int,
    val showSystemInfo: Boolean,
    val sections: List<TabIntroSection>
)

object TabIntroContent {
    /** 下标与主界面 Tab 顺序一致：0 终端 / 1 笔记 / 2 文件 / 3 网络 / 4 设置。 */
    fun page(index: Int): TabIntroPage = when (index) {
        0 -> TabIntroPage(
            iconRes = R.drawable.ic_tab_terminal,
            titleRes = R.string.tab_terminal,
            leadRes = R.string.intro_terminal_lead,
            showSystemInfo = true,
            sections = listOf(
                TabIntroSection(R.string.intro_terminal_shortcut_title, R.string.intro_terminal_shortcut_body),
                TabIntroSection(R.string.intro_terminal_wheel_title, R.string.intro_terminal_wheel_body),
                TabIntroSection(R.string.intro_terminal_library_title, R.string.intro_terminal_library_body),
                TabIntroSection(R.string.intro_terminal_startup_title, R.string.intro_terminal_startup_body),
                TabIntroSection(R.string.intro_terminal_ui_title, R.string.intro_terminal_ui_body),
                TabIntroSection(R.string.intro_terminal_workshop_title, R.string.intro_terminal_workshop_body)
            )
        )
        1 -> TabIntroPage(
            iconRes = R.drawable.ic_tab_notes,
            titleRes = R.string.tab_notes,
            leadRes = R.string.intro_notes_lead,
            showSystemInfo = false,
            sections = listOf(
                TabIntroSection(R.string.intro_notes_list_title, R.string.intro_notes_list_body),
                TabIntroSection(R.string.intro_notes_tags_title, R.string.intro_notes_tags_body),
                TabIntroSection(R.string.intro_notes_todo_title, R.string.intro_notes_todo_body),
                TabIntroSection(R.string.intro_notes_autosave_title, R.string.intro_notes_autosave_body),
                TabIntroSection(R.string.intro_notes_tile_title, R.string.intro_notes_tile_body)
            )
        )
        2 -> TabIntroPage(
            iconRes = R.drawable.ic_tab_files,
            titleRes = R.string.tab_files,
            leadRes = R.string.intro_files_lead,
            showSystemInfo = false,
            sections = listOf(
                TabIntroSection(R.string.intro_files_nav_title, R.string.intro_files_nav_body),
                TabIntroSection(R.string.intro_files_import_title, R.string.intro_files_import_body),
                TabIntroSection(R.string.intro_files_share_title, R.string.intro_files_share_body),
                TabIntroSection(R.string.intro_files_delete_title, R.string.intro_files_delete_body),
                TabIntroSection(R.string.intro_files_types_title, R.string.intro_files_types_body)
            )
        )
        3 -> TabIntroPage(
            iconRes = R.drawable.ic_tab_network,
            titleRes = R.string.tab_network,
            leadRes = R.string.intro_network_lead,
            showSystemInfo = false,
            sections = listOf(
                TabIntroSection(R.string.intro_network_capture_title, R.string.intro_network_capture_body),
                TabIntroSection(R.string.intro_network_log_title, R.string.intro_network_log_body),
                TabIntroSection(R.string.intro_network_rank_title, R.string.intro_network_rank_body),
                TabIntroSection(R.string.intro_network_proxy_title, R.string.intro_network_proxy_body)
            )
        )
        else -> TabIntroPage(
            iconRes = R.drawable.ic_tab_settings,
            titleRes = R.string.tab_settings,
            leadRes = R.string.intro_settings_lead,
            showSystemInfo = false,
            sections = listOf(
                TabIntroSection(R.string.intro_settings_ui_title, R.string.intro_settings_ui_body),
                TabIntroSection(R.string.intro_settings_commands_title, R.string.intro_settings_commands_body),
                TabIntroSection(R.string.intro_settings_workshop_title, R.string.intro_settings_workshop_body),
                TabIntroSection(R.string.intro_settings_net_title, R.string.intro_settings_net_body)
            )
        )
    }
}
