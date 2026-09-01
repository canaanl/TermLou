package com.workspace.proot

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
            typeface = Typeface.MONOSPACE
            setTextColor(UiTokens.statusGreen)
            textSize = UiTokens.TEXT_META
            setPadding(16, 8, 16, 0)
            setBackgroundColor(theme.surfaceVariant)
        }
    }

    fun createTabHost(): Pair<FrameLayout, View> {
        val density = activity.resources.displayMetrics.density
        val tabTextSize = UiTokens.TEXT_TITLE

        val tabHost = FrameLayout(activity).apply {
            setBackgroundColor(theme.surfaceVariant)
        }
        val tabIndicator = View(activity).apply {
            val r = (tabTextSize + 16 - 4) / 2f * density
            background = GradientDrawable().apply {
                setColor(theme.surface)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            layoutParams = FrameLayout.LayoutParams(1, (tabTextSize * density + 16 * density - 4 * density).toInt()).apply {
                gravity = Gravity.BOTTOM
            }
        }
        tabHost.addView(tabIndicator)

        return Pair(tabHost, tabIndicator)
    }

    fun createTabBar(): List<TextView> {
        val tabTextSize = UiTokens.TEXT_TITLE
        val toolbarBg = theme.surfaceVariant
        val labels = listOf("终端", "文件", "网络", "设置")

        return labels.map { label ->
            TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(toolbarBg)
                textSize = tabTextSize
                setPadding(24, 8, 24, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
    }

    fun createSetupArea(
        onInstallClick: (Button) -> Unit
    ): SetupAreaViews {
        val setupArea = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        setupArea.addView(TextView(activity).apply {
            text = "RootFS 安装"
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_TITLE
            setPadding(0, 0, 0, 16)
        })
        val setupBtn = Button(activity).apply {
            text = "解压 RootFS"
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            setOnClickListener { onInstallClick(this) }
            ButtonStyle.apply(this, theme.primary)
        }
        val progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48)
            max = 100
        }
        val progressText = TextView(activity).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_COMPACT
        }
        setupArea.addView(setupBtn)
        setupArea.addView(progressBar)
        setupArea.addView(progressText)

        return SetupAreaViews(setupArea, setupBtn, progressBar, progressText)
    }

    fun createShortcutContainer(): ShortcutViews {
        val shortcutInner = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surfaceVariant)
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
            setPadding(4, 0, 4, 0)
            setBackgroundColor(theme.surface)
        }
        val importBtn = Button(activity).apply {
            text = "导入文件"
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            setPadding(3, 0, 3, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            ButtonStyle.apply(this, theme.outline)
            setOnClickListener { onImportClick() }
        }
        val importFolderBtn = Button(activity).apply {
            text = "导入文件夹"
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            setPadding(3, 0, 3, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            ButtonStyle.apply(this, theme.outline)
            setOnClickListener { onImportFolderClick() }
        }
        fileBottomBar.addView(importBtn)
        fileBottomBar.addView(importFolderBtn)
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
            textSize = UiTokens.TEXT_META
            setPadding(3, 0, 3, 0)
            layoutParams = if (widthPx > 0) {
                LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            ButtonStyle.apply(this, theme.outline)
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
            textSize = UiTokens.TEXT_META
            setPadding(3, 0, 3, 0)
            layoutParams = if (widthPx > 0) {
                LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            ButtonStyle.apply(this, theme.outline)
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
