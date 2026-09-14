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
            textSize = UiTokens.TEXT_BODY
            setPadding(16, 8, 16, 0)
            setBackgroundColor(theme.surfaceContainer)
        }
    }

    fun createTabHost(): Pair<FrameLayout, View> {
        val density = activity.resources.displayMetrics.density

        val tabHost = FrameLayout(activity).apply {
            setBackgroundColor(theme.surfaceContainer)
        }
        val tabIndicator = View(activity).apply {
            background = GradientDrawable().apply {
                setColor(theme.primary)
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
            R.drawable.ic_tab_files to R.string.tab_files,
            R.drawable.ic_tab_network to R.string.tab_network,
            R.drawable.ic_tab_settings to R.string.tab_settings
        )

        return icons.map { (icon, desc) ->
            ImageView(activity).apply {
                setImageResource(icon)
                contentDescription = activity.getString(desc)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(8, 12, 8, 12)
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
            text = activity.getString(R.string.setup_title)
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = UiTokens.TEXT_TITLE
            setPadding(0, 0, 0, 16)
        })
        val setupBtn = Button(activity).apply {
            text = activity.getString(R.string.setup_extract)
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
            setPadding(4, 0, 4, 0)
            setBackgroundColor(theme.surface)
        }
        val importBtn = Button(activity).apply {
            text = activity.getString(R.string.setup_import_file)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            setPadding(3, 0, 3, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onImportClick() }
        }
        val importFolderBtn = Button(activity).apply {
            text = activity.getString(R.string.setup_import_folder)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            setPadding(3, 0, 3, 0)
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
                SegmentStyle.Fill(theme.secondaryContainer, theme.onSecondaryContainer),
                null
            ),
            theme.outline,
            theme.onSurface
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
            textSize = UiTokens.TEXT_META
            setPadding(3, 0, 3, 0)
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
            textSize = UiTokens.TEXT_META
            setPadding(3, 0, 3, 0)
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
