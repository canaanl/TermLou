package com.workspace.proot

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

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
                        else -> Color.WHITE
                    }
                )
                it.isAllCaps = true
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
