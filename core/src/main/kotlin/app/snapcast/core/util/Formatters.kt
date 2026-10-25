package app.snapcast.core.util

object Formatters {
    /** "1.4 GB", "730 MB", "12 KB", "512 B". */
    fun fileSize(bytes: Long): String = TODO()

    /** "1:02:03" / "4:05" (h:mm:ss when >= 1h). Negative or NaN -> "0:00". */
    fun duration(seconds: Double): String = TODO()

    fun durationMs(ms: Long): String = duration(ms / 1000.0)
}
