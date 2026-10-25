package app.snapcast.player.pip

import kotlin.math.roundToInt

/**
 * Picture-in-picture aspect ratio as an exact fraction. Android rejects ratios outside 1:2.39 .. 2.39:1
 * (enterPictureInPictureMode throws), so wider or taller videos are clamped to those limits.
 */
internal data class PipAspectRatio(val numerator: Int, val denominator: Int) {

    companion object {
        /** Widest ratio Android accepts (2.39:1). */
        val MAX = PipAspectRatio(239, 100)

        /** Tallest ratio Android accepts (1:2.39). */
        val MIN = PipAspectRatio(100, 239)

        /**
         * Display aspect ratio of a [width] x [height] video whose pixels are [pixelWidthHeightRatio] wide
         * (anamorphic content), clamped to [MIN]..[MAX]; null while the size is unknown (e.g. audio only).
         */
        fun of(width: Int, height: Int, pixelWidthHeightRatio: Float = 1f): PipAspectRatio? {
            if (width <= 0 || height <= 0) return null
            val pixelRatio = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
            val displayWidth = (width * pixelRatio.toDouble()).roundToInt()
            if (displayWidth <= 0) return null
            // Cross-multiplied in Long so the comparison is exact and cannot overflow.
            return when {
                displayWidth.toLong() * MAX.denominator > height.toLong() * MAX.numerator -> MAX
                displayWidth.toLong() * MIN.denominator < height.toLong() * MIN.numerator -> MIN
                else -> PipAspectRatio(displayWidth, height)
            }
        }
    }
}
