package dev.essentialshare.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Interface language: Russian when the system is Russian, English otherwise. */
object Lang {
    /** Snapshot state, so every screen redraws the moment the language is switched. */
    var ru: Boolean by mutableStateOf(Locale.getDefault().language == "ru")

    /** "" follows the system, otherwise "ru" or "en". */
    fun apply(code: String) {
        ru = when (code) {
            "ru" -> true
            "en" -> false
            else -> Locale.getDefault().language == "ru"
        }
    }
}

fun tr(en: String, ru: String) = if (Lang.ru) ru else en

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    val s = if (v >= 100) "%.0f".format(v) else if (v >= 10) "%.1f".format(v) else "%.2f".format(v)
    return "${s.replace(',', '.')} ${units[i]}"
}

fun formatSpeed(bps: Long) = formatSize(bps) + "/s"

fun formatTime(ms: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}
