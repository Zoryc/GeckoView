package app.snapcast.core.util

import java.util.Locale

object Formatters {
    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    /**
     * "1.4 GB", "730 MB", "12 KB", "512 B". Binary (1024) multiples, US locale, one decimal for KB and
     * above with a trailing ".0" dropped. Negative sizes format as "0 B".
     */
    fun fileSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes.coerceAtLeast(0)} B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < UNITS.lastIndex) {
            value /= 1024
            unit++
        }
        var rounded = Math.round(value * 10) / 10.0
        if (rounded >= 1024 && unit < UNITS.lastIndex) {
            rounded = Math.round(rounded / 1024 * 10) / 10.0
            unit++
        }
        val number = if (rounded % 1.0 == 0.0) {
            String.format(Locale.US, "%d", rounded.toLong())
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
        return "$number ${UNITS[unit]}"
    }

    /** "1:02:03" / "4:05" (h:mm:ss when >= 1h). Negative or NaN -> "0:00". */
    fun duration(seconds: Double): String {
        if (seconds.isNaN() || seconds.isInfinite() || seconds < 0) return "0:00"
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    fun durationMs(ms: Long): String = duration(ms / 1000.0)
}
