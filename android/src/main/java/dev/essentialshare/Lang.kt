package dev.essentialshare

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import java.util.Locale

/** Two-language UI (Russian / English); the choice is the app's own locale, so every Activity and Service follows it. */
object Lang {
    val isRu: Boolean get() = Locale.getDefault().language == "ru"

    /** "" = follow the phone, otherwise "ru" or "en". */
    fun current(ctx: Context): String =
        ctx.getSystemService(LocaleManager::class.java).applicationLocales.let { if (it.isEmpty) "" else it[0].language }

    fun set(ctx: Context, code: String) {
        ctx.getSystemService(LocaleManager::class.java).applicationLocales =
            if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
    }
}

fun tr(en: String, ru: String): String = if (Lang.isRu) ru else en

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    val s = if (v >= 100) "%.0f".format(Locale.US, v) else if (v >= 10) "%.1f".format(Locale.US, v) else "%.2f".format(Locale.US, v)
    return "$s ${units[i]}"
}

fun formatSpeed(bps: Long) = formatSize(bps) + "/s"

fun formatTime(ms: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}
