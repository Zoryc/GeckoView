package app.snapcast.cast.chromecast

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueuePositionsTest {

    @Test
    fun `inserts before the item following the current one`() {
        assertThat(QueuePositions.insertBeforeId(listOf(3, 7, 9), currentItemId = 3)).isEqualTo(7)
        assertThat(QueuePositions.insertBeforeId(listOf(3, 7, 9), currentItemId = 7)).isEqualTo(9)
    }

    @Test
    fun `appends when the current item is last or unknown`() {
        assertThat(QueuePositions.insertBeforeId(listOf(3, 7, 9), currentItemId = 9)).isNull()
        assertThat(QueuePositions.insertBeforeId(listOf(3, 7, 9), currentItemId = 4)).isNull()
        assertThat(QueuePositions.insertBeforeId(listOf(3, 7, 9), currentItemId = null)).isNull()
        assertThat(QueuePositions.insertBeforeId(emptyList(), currentItemId = 1)).isNull()
    }
}
