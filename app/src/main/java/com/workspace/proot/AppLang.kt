package com.workspace.proot

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.io.File
import java.util.Locale

/**
 * 应用语言：首次跟随系统（英文系统用英文，其余用中文），
 * 设置开关切换后持久化并重建界面。通知渠道名随语言重建。
 */
object AppLang {
    const val LANG_ZH = "zh"
    const val LANG_EN = "en"

    fun isChinese(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("term-lou-settings", Context.MODE_PRIVATE)
        return when (prefs.getString("langExplicit", "")) {
            LANG_ZH -> true
            LANG_EN -> false
            else -> LocaleListCompat.getAdjustedDefault()[0]?.language != "en"
        }
    }

    fun apply(ctx: Context) {
        val zh = isChinese(ctx)
        AppCompatDelegate.setApplicationLocales(
            if (zh) LocaleListCompat.create(Locale("zh"))
            else LocaleListCompat.create(Locale.ENGLISH)
        )
        runCatching {
            File(File(ctx.filesDir, ".termlou"), "lang")
                .apply { parentFile?.mkdirs() }
                .writeText(if (zh) "zh" else "en")
        }
        recreateChannels(ctx)
    }

    private fun recreateChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            listOf("term-lou-net", "term-lou-command", "term-lou-keepalive", "term-lou-lan", "term-lou-crash")
                .forEach { runCatching { nm.deleteNotificationChannel(it) } }
        }
    }
}
