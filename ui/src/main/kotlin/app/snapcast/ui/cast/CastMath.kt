package app.snapcast.ui.cast

import java.util.Locale
import kotlin.math.abs

/**
 * Queue index arithmetic. Target indexes use the semantics of both `CastTarget.moveQueueItem` and
 * `CastController.movePending`: the index the item ends up at once it has been moved.
 */
internal object QueueMath {
    /**
     * Index that makes [itemId] play right after [currentId], or null when it already does, when it is the
     * current item, or when either id is not in [ids].
     */
    fun playNextIndex(ids: List<Int>, itemId: Int, currentId: Int?): Int? {
        val from = ids.indexOf(itemId)
        val current = currentId?.let { ids.indexOf(it) } ?: -1
        if (from < 0 || current < 0 || from == current || from == current + 1) return null
        // Items before the current one shift it left by one when they are taken out.
        return if (from < current) current else current + 1
    }

    /** [from] moved by [delta] places within a list of [size] items, or null when it cannot move that way. */
    fun shiftedIndex(size: Int, from: Int, delta: Int): Int? {
        if (from !in 0 until size || delta == 0) return null
        val to = (from + delta).coerceIn(0, size - 1)
        return if (to == from) null else to
    }

    /** Whether there is an item before / after [currentId] in [ids] (false for both when it is unknown). */
    fun neighbours(ids: List<Int>, currentId: Int?): Pair<Boolean, Boolean> {
        val index = currentId?.let { ids.indexOf(it) } ?: -1
        if (index < 0) return false to false
        return (index > 0) to (index < ids.lastIndex)
    }
}

/** Formatting and option helpers of the expanded cast controller. */
internal object CastControls {
    val PLAYBACK_RATES: List<Double> = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

    const val SEEK_STEP_MS = 10_000L

    /** The offered rate closest to [rate] (receivers may report e.g. 0.9999). */
    fun nearestRate(rate: Double): Double = PLAYBACK_RATES.minBy { abs(it - rate) }

    /** "0.5×", "1×", "1.25×". */
    fun rateLabel(rate: Double): String {
        val text = if (rate % 1.0 == 0.0) rate.toLong().toString() else String.format(Locale.US, "%.2f", rate).trimEnd('0')
        return "$text×"
    }

    /** Position as a 0..1 fraction of [durationMs]; 0 when the duration is unknown. */
    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()

    fun positionAt(fraction: Float, durationMs: Long): Long =
        (fraction.coerceIn(0f, 1f).toDouble() * durationMs.coerceAtLeast(0)).toLong()

    fun remainingMs(positionMs: Long, durationMs: Long): Long = (durationMs - positionMs).coerceAtLeast(0)
}
