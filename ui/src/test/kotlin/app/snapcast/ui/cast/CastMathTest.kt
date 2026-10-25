package app.snapcast.ui.cast

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CastMathTest {
    /** Applies a "move to final index" like the receiver / pending queue do. */
    private fun List<Int>.moved(item: Int, toIndex: Int): List<Int> =
        toMutableList().apply {
            remove(item)
            add(toIndex, item)
        }

    private val ids = listOf(10, 11, 12, 13, 14)

    @Test
    fun `play next moves a later item right after the current one`() {
        val index = QueueMath.playNextIndex(ids, itemId = 14, currentId = 11)
        assertThat(index).isEqualTo(2)
        assertThat(ids.moved(14, index!!)).containsExactly(10, 11, 14, 12, 13).inOrder()
    }

    @Test
    fun `play next moves an earlier item right after the current one`() {
        val index = QueueMath.playNextIndex(ids, itemId = 10, currentId = 12)
        assertThat(index).isEqualTo(2)
        assertThat(ids.moved(10, index!!)).containsExactly(11, 12, 10, 13, 14).inOrder()
    }

    @Test
    fun `play next is a no-op for the current and the next item`() {
        assertThat(QueueMath.playNextIndex(ids, itemId = 12, currentId = 12)).isNull()
        assertThat(QueueMath.playNextIndex(ids, itemId = 13, currentId = 12)).isNull()
        assertThat(QueueMath.playNextIndex(ids, itemId = 99, currentId = 12)).isNull()
        assertThat(QueueMath.playNextIndex(ids, itemId = 13, currentId = null)).isNull()
        assertThat(QueueMath.playNextIndex(ids, itemId = 13, currentId = 99)).isNull()
    }

    @Test
    fun `shifted index stays within bounds`() {
        assertThat(QueueMath.shiftedIndex(size = 5, from = 2, delta = -1)).isEqualTo(1)
        assertThat(QueueMath.shiftedIndex(size = 5, from = 2, delta = 1)).isEqualTo(3)
        assertThat(QueueMath.shiftedIndex(size = 5, from = 0, delta = -1)).isNull()
        assertThat(QueueMath.shiftedIndex(size = 5, from = 4, delta = 1)).isNull()
        assertThat(QueueMath.shiftedIndex(size = 5, from = 1, delta = 10)).isEqualTo(4)
        assertThat(QueueMath.shiftedIndex(size = 5, from = -1, delta = 1)).isNull()
        assertThat(QueueMath.shiftedIndex(size = 5, from = 2, delta = 0)).isNull()
    }

    @Test
    fun `moving down by one with final-index semantics swaps neighbours`() {
        val to = QueueMath.shiftedIndex(ids.size, from = 1, delta = 1)!!
        assertThat(ids.moved(11, to)).containsExactly(10, 12, 11, 13, 14).inOrder()
    }

    @Test
    fun `neighbours of the current item`() {
        assertThat(QueueMath.neighbours(ids, 10)).isEqualTo(false to true)
        assertThat(QueueMath.neighbours(ids, 12)).isEqualTo(true to true)
        assertThat(QueueMath.neighbours(ids, 14)).isEqualTo(true to false)
        assertThat(QueueMath.neighbours(ids, null)).isEqualTo(false to false)
        assertThat(QueueMath.neighbours(listOf(1), 1)).isEqualTo(false to false)
    }

    @Test
    fun `playback rates and labels`() {
        assertThat(CastControls.nearestRate(0.9999)).isEqualTo(1.0)
        assertThat(CastControls.nearestRate(1.6)).isEqualTo(1.5)
        assertThat(CastControls.nearestRate(5.0)).isEqualTo(2.0)
        assertThat(CastControls.PLAYBACK_RATES.map(CastControls::rateLabel))
            .containsExactly("0.5×", "0.75×", "1×", "1.25×", "1.5×", "1.75×", "2×").inOrder()
    }

    @Test
    fun `seek fractions and remaining time`() {
        assertThat(CastControls.fraction(30_000, 120_000)).isEqualTo(0.25f)
        assertThat(CastControls.fraction(500_000, 120_000)).isEqualTo(1f)
        assertThat(CastControls.fraction(10, 0)).isEqualTo(0f)
        assertThat(CastControls.positionAt(0.5f, 120_000)).isEqualTo(60_000L)
        assertThat(CastControls.positionAt(2f, 120_000)).isEqualTo(120_000L)
        assertThat(CastControls.remainingMs(100_000, 120_000)).isEqualTo(20_000L)
        assertThat(CastControls.remainingMs(130_000, 120_000)).isEqualTo(0L)
    }
}
