package app.snapcast.ui.util

import java.util.Locale

/** `#rrggbb` helpers for the HTML color input. */
object ColorHex {
    private val sixDigits = Regex("^[0-9a-fA-F]{6}$")
    private val threeDigits = Regex("^[0-9a-fA-F]{3}$")

    /** Normalizes "#ABC", "abc", "#AABBCC", " aabbcc " to "#aabbcc"; null when not a valid hex color. */
    fun normalize(input: String?): String? {
        val hex = input?.trim()?.removePrefix("#") ?: return null
        val full = when {
            sixDigits.matches(hex) -> hex
            threeDigits.matches(hex) -> hex.map { "$it$it" }.joinToString("")
            else -> return null
        }
        return "#" + full.lowercase(Locale.ROOT)
    }

    /** ARGB int (opaque) for a valid hex color, else null. */
    fun toArgb(input: String?): Int? {
        val normalized = normalize(input) ?: return null
        return (0xFF000000L or normalized.substring(1).toLong(16)).toInt()
    }

    /** "#rrggbb" for an ARGB int (alpha ignored). */
    fun fromArgb(argb: Int): String = "#%06x".format(Locale.ROOT, argb and 0xFFFFFF)
}
