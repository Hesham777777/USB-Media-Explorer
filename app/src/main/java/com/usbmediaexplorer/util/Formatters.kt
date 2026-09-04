package com.usbmediaexplorer.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Human readable formatting helpers. Everything is locale aware so the Arabic UI gets
 * Arabic-Indic digits when the locale asks for them.
 */
object Formatters {

    fun size(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes <= 0) return "0 ${unit(0, locale)}"
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        val exp = min((ln(bytes.toDouble()) / ln(1024.0)).toInt(), units.lastIndex)
        val value = bytes / 1024.0.pow(exp.toDouble())
        val decimals = if (exp <= 1) 0 else if (value < 10) 2 else if (value < 100) 1 else 0
        return String.format(locale, "%,.${decimals}f %s", value, unit(exp, locale))
    }

    private fun unit(exp: Int, locale: Locale): String = when (exp) {
        0 -> if (locale.language == "ar") "بايت" else "B"
        1 -> if (locale.language == "ar") "ك.ب" else "KB"
        2 -> if (locale.language == "ar") "م.ب" else "MB"
        3 -> if (locale.language == "ar") "ج.ب" else "GB"
        4 -> if (locale.language == "ar") "ت.ب" else "TB"
        else -> if (locale.language == "ar") "ب.ب" else "PB"
    }

    /** 2:04:18 or 04:18 */
    fun duration(millis: Long): String {
        if (millis <= 0) return "0:00"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** Short remaining-time text used for transfer ETAs: "1 h 12 min", "8 min", "45 s". */
    fun eta(millis: Long, locale: Locale = Locale.getDefault()): String {
        if (millis <= 0) return "--"
        val ar = locale.language == "ar"
        val seconds = (millis / 1000).coerceAtMost(60 * 60 * 24)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> if (ar) "$h س $m د" else "${h}h ${m}m"
            m > 0 -> if (ar) "$m د" else "${m}m ${s}s"
            else -> if (ar) "$s ث" else "${s}s"
        }
    }

    fun speed(bytesPerSecond: Double, locale: Locale = Locale.getDefault()): String =
        size(bytesPerSecond.roundToInt().toLong(), locale) + "/s"

    fun dateTime(epochMillis: Long, locale: Locale = Locale.getDefault()): String {
        if (epochMillis <= 0) return ""
        val pattern = if (locale.language == "ar") "yyyy/MM/dd hh:mm a" else "MMM d, yyyy h:mm a"
        return runCatching { SimpleDateFormat(pattern, locale).format(Date(epochMillis)) }
            .getOrDefault("")
    }

    fun date(epochMillis: Long, locale: Locale = Locale.getDefault()): String {
        if (epochMillis <= 0) return ""
        val pattern = if (locale.language == "ar") "yyyy/MM/dd" else "MMM d, yyyy"
        return runCatching { SimpleDateFormat(pattern, locale).format(Date(epochMillis)) }
            .getOrDefault("")
    }

    /** "4K", "1080p", "720p" — falls back to the raw WxH for unusual sizes. */
    fun resolution(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return ""
        val shortSide = min(width, height)
        val longSide = max(width, height)
        return when {
            shortSide >= 4000 || longSide >= 15000 -> "8K"
            shortSide >= 2000 || longSide >= 7000 -> "4K"
            shortSide >= 1300 -> "1440p"
            shortSide >= 1000 -> "1080p"
            shortSide >= 680 -> "720p"
            shortSide >= 440 -> "480p"
            shortSide >= 320 -> "360p"
            shortSide >= 200 -> "240p"
            else -> "${width}×${height}"
        }
    }

    fun fps(value: Float): String {
        if (value <= 0f) return ""
        val rounded = (value * 100).roundToInt() / 100f
        return if (abs(rounded - rounded.roundToInt()) < 0.01f) {
            "${rounded.roundToInt()} fps"
        } else {
            String.format(Locale.US, "%.2f fps", rounded)
        }
    }

    fun percent(part: Long, total: Long): Int =
        if (total <= 0) 0 else ((part.toDouble() / total.toDouble()) * 100).roundToInt().coerceIn(0, 100)

    fun todayStart(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
