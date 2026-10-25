package app.snapcast.cast.service

import kotlin.math.roundToInt
import kotlin.math.sign

/** Maps the receiver volume (0.0..1.0) to the absolute steps exposed through the media session. */
internal object VolumeSteps {
    const val MAX = 20

    fun toSteps(level: Double): Int =
        if (level.isNaN()) 0 else (level.coerceIn(0.0, 1.0) * MAX).roundToInt()

    fun toLevel(steps: Int): Double = steps.coerceIn(0, MAX) / MAX.toDouble()

    /** One step in [direction] (negative = down, positive = up, 0 = none), clamped to 0..[MAX]. */
    fun adjust(current: Int, direction: Int): Int = (current + direction.sign).coerceIn(0, MAX)
}
