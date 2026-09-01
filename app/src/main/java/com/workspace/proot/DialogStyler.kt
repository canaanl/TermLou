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
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(theme.primary)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.WHITE)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(theme.error)
    }
}
