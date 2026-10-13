package com.workspace.proot

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.widget.ImageView
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.HorizontalScrollView

class UiBuilder(
    private val activity: MainActivity,
    private val theme: ThemeColors
) {
    fun createStatusBar(): TextView {
        return TextView(activity).apply {
            text = "Workspace Terminal"
            setTextColor(theme.onSurface)
            // 5.9.47：状态栏拆两块后，本View 是**块②**（内容区）。
            //   - 不设行高：它是一行提示条，行高只会平白占高度。
            //   - **上下内距对称**（原为 (16,8,16,0)，底部 0）：
            //     两块各自按内容定高 + 垂直居中时，底部 0 会让下伸字母
            //     （Settings 的 g、next key 的 y）超出盒子上沿被切 —— 用户实机报过。
            //   - 背景**不在这里设**：底色画在父容器上（StatusController.createStatusWrap），
            //     两块都透明才是"看不出分块"的前提。
            textSize = UiTokens.TEXT_COMPACT
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            includeFontPadding = true
            setPadding(16, activity.dp(UiTokens.SPACE_2), 16, activity.dp(UiTokens.SPACE_2))
        }
    }

    fun createTabHost(): Pair<FrameLayout, View> {
        val density = activity.resources.displayMetrics.density

        val tabHost = FrameLayout(activity).apply {
            setBackgroundColor(theme.surfaceContainer)
        }
        val tabIndicator = View(activity).apply {
            background = GradientDrawable().apply {
                setColor(theme.primaryVisible)
                cornerRadius = 0f
            }
            layoutParams = FrameLayout.LayoutParams(1, (3 * density).toInt()).apply {
                gravity = Gravity.BOTTOM
            }
        }
        tabHost.addView(tabIndicator)

        return Pair(tabHost, tabIndicator)
    }

    fun createTabBar(): List<ImageView> {
        val icons = listOf(
            R.drawable.ic_tab_terminal to R.string.tab_terminal,
            R.drawable.ic_tab_notes to R.string.tab_notes,
            R.drawable.ic_tab_files to R.string.tab_files,
            R.drawable.ic_tab_network to R.string.tab_network,
            R.drawable.ic_tab_settings to R.string.tab_settings
        )

        return icons.map { (icon, desc) ->
            ImageView(activity).apply {
                setImageResource(icon)
                contentDescription = activity.getString(desc)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPaddingMd(UiTokens.SPACE_2, UiTokens.SPACE_3, UiTokens.SPACE_2, UiTokens.SPACE_3)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
    }

    fun createSetupArea(
        onInstallClick: (Button) -> Unit
    ): SetupAreaViews {
        val setupArea = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(UiTokens.SPACE_6, UiTokens.SPACE_6, UiTokens.SPACE_6, UiTokens.SPACE_6)
        }
        setupArea.addView(TextView(activity).apply {
            text = activity.getString(R.string.setup_title)
            setTextColor(theme.onSurface)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textMd(UiTokens.TEXT_TITLE, UiTokens.LINE_TITLE)
            setPaddingMd(UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_0, UiTokens.SPACE_4)
        })
        val setupBtn = Button(activity).apply {
            text = activity.getString(R.string.setup_extract)
            setTextColor(theme.primaryVisible)
            textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
            setOnClickListener { onInstallClick(this) }
            ButtonStyle.outlined(this, theme.primaryVisible)
        }
        val progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48)
            max = 100
        }
        val progressText = TextView(activity).apply {
            text = ""
            setTextColor(theme.onSurface)
            textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
        }
        setupArea.addView(setupBtn)
        setupArea.addView(progressBar)
        setupArea.addView(progressText)

        return SetupAreaViews(setupArea, setupBtn, progressBar, progressText)
    }

    fun createShortcutContainer(): ShortcutViews {
        val shortcutInner = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surfaceContainer)
        }
        val shortcutContainer = HorizontalScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(shortcutInner)
            isHorizontalScrollBarEnabled = false
        }
        return ShortcutViews(shortcutContainer, shortcutInner)
    }

    fun createFileBottomBar(
        onImportClick: () -> Unit,
        onImportFolderClick: () -> Unit
    ): LinearLayout {
        val fileBottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingMd(UiTokens.SPACE_1, UiTokens.SPACE_0, UiTokens.SPACE_1, UiTokens.SPACE_0)
            setBackgroundColor(theme.surface)
        }
        val importBtn = Button(activity).apply {
            text = activity.getString(R.string.setup_import_file)
            setTextColor(Color.WHITE)
            textSize = AppLang.bottomButtonTextSize(activity)
            // 5.9.40：原 3dp 不在 4dp 网格上，按 MD3 上调到 4dp（SPACE_1）
            setPaddingMd(UiTokens.SPACE_1, 0, UiTokens.SPACE_1, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onImportClick() }
        }
        val importFolderBtn = Button(activity).apply {
            text = activity.getString(R.string.setup_import_folder)
            setTextColor(Color.WHITE)
            textSize = AppLang.bottomButtonTextSize(activity)
            // 5.9.40：原 3dp 不在 4dp 网格上，按 MD3 上调到 4dp（SPACE_1）
            setPaddingMd(UiTokens.SPACE_1, 0, UiTokens.SPACE_1, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onImportFolderClick() }
        }
        fileBottomBar.addView(importBtn)
        fileBottomBar.addView(importFolderBtn)
        SegmentStyle.applyRow(
            fileBottomBar,
            listOf(
                null,
                null
            ),
            theme.outline,
            theme.onSurface,
            SegmentStyle.Bar(0f, false)
        )
        return fileBottomBar
    }

    fun createShortcutKey(
        label: String,
        seq: String,
        hasCtrl: Boolean = false,
        ctrlSeq: String = "",
        widthPx: Int = 0,
        onClick: (String, Boolean, String) -> Unit
    ): Button {
        return Button(activity).apply {
            text = label
            setTextColor(Color.WHITE)
            textMd(UiTokens.TEXT_META, UiTokens.LINE_META)
            // 5.9.40：原 3dp 不在 4dp 网格上，按 MD3 上调到 4dp（SPACE_1）
            setPaddingMd(UiTokens.SPACE_1, 0, UiTokens.SPACE_1, 0)
            // 5.9.41：统一圆角来源（此前是 MD2 主题自带，与设置页按钮不一致）
            ButtonStyle.apply(this, theme.primary, theme.primaryVisible)
            layoutParams = if (widthPx > 0) {
                LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            setOnClickListener { onClick(seq, hasCtrl, ctrlSeq) }
        }
    }

    fun createCtrlKey(
        widthPx: Int = 0,
        onClick: () -> Unit
    ): Button {
        return Button(activity).apply {
            text = "Ctrl"
            setTextColor(Color.WHITE)
            textMd(UiTokens.TEXT_META, UiTokens.LINE_META)
            // 5.9.40：原 3dp 不在 4dp 网格上，按 MD3 上调到 4dp（SPACE_1）
            setPaddingMd(UiTokens.SPACE_1, 0, UiTokens.SPACE_1, 0)
            // 5.9.41：此前没走 ButtonStyle，用的是 MD2 主题自带圆角，
            // 与设置页按钮（CORNER_MD）并排就是两种圆角。统一到同一来源。
            ButtonStyle.apply(this, theme.primary, theme.primaryVisible)
            layoutParams = if (widthPx > 0) {
                LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            setOnClickListener { onClick() }
        }
    }

    data class ShortcutViews(
        val container: HorizontalScrollView,
        val inner: LinearLayout
    )

    data class SetupAreaViews(
        val area: LinearLayout,
        val button: Button,
        val progressBar: android.widget.ProgressBar,
        val progressText: TextView
    )
}
