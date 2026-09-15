package com.workspace.proot

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

object DialogStyler {
    /** 给原生 AlertDialog 套一层深色圆角皮肤 + 强调按钮色。需在 dialog.show() 之后调用。 */
    fun apply(dialog: AlertDialog, theme: ThemeColors) {
        val d = dialog.context.resources.displayMetrics.density
        val radius = (28 * d).toFloat()
        val bg = GradientDrawable().apply {
            setColor(theme.surfaceVariant)
            cornerRadius = radius
        }
        dialog.window?.setBackgroundDrawable(bg)
        // 原生标题/消息默认跟 Activity 主题（深色白字）：浅色下看不见，统一走主题色。
        // 全站弹窗都经此处，一处管全部（排行标题、编辑标题、LAN 验证标题、确认消息）。
        // alertTitle 非公开 SDK id，按名运行时解析，取不到则跳过。
        val alertTitleId = dialog.context.resources.getIdentifier("alertTitle", "id", "android")
        if (alertTitleId != 0) dialog.findViewById<TextView>(alertTitleId)?.setTextColor(theme.onSurface)
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(theme.onSurface)
        // 统一英文按键为首字母大写：关掉系统默认全大写（中文不受影响）
        for (which in listOf(
            AlertDialog.BUTTON_POSITIVE,
            AlertDialog.BUTTON_NEGATIVE,
            AlertDialog.BUTTON_NEUTRAL
        )) {
            dialog.getButton(which)?.let {
                it.setTextColor(
                    when (which) {
                        AlertDialog.BUTTON_POSITIVE -> theme.primary
                        AlertDialog.BUTTON_NEUTRAL -> theme.error
                        else -> theme.onSurface
                    }
                )
                it.isAllCaps = true
            }
        }
        // setItems 列表弹窗的行文字走平台主题色（浅色下会残留白字），统一着主题前景色。
        // 需等 ListView 完成布局、行 view 建立后再上色（紧随 show() 同步执行时 childCount 为 0）。
        dialog.listView?.post {
            for (i in 0 until dialog.listView.childCount) {
                (dialog.listView.getChildAt(i) as? TextView)?.setTextColor(theme.onSurface)
            }
        }
    }

}

/** 建窗即套统一样式（深色圆角 + 按键首字母大写），替代裸 show()。 */
fun AlertDialog.Builder.showStyled(theme: ThemeColors): AlertDialog {
    val dialog = create()
    dialog.show()
    DialogStyler.apply(dialog, theme)
    return dialog
}
