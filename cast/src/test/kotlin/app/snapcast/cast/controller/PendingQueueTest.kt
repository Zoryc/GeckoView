package app.snapcast.cast.controller

import app.snapcast.cast.PendingQueueItem
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PendingQueueTest {

    private fun video(id: String) = DetectedVideo(id = id, tabId = "t", url = "https://e.com/$id.mp4", type = StreamType.MP4)

    private fun item(key: String) = PendingQueueItem(key, video(key))

    @Test
    fun `keys combine the video id and a unique timestamp`() {
        val existing = listOf(PendingQueueItem("a-100", video("a")))
        assertThat(PendingQueue.newKey(video("a"), 100, existing)).isEqualTo("a-101")
        assertThat(PendingQueue.newKey(video("b"), 100, existing)).isEqualTo("b-100")
    }

    @Test
    fun `play next goes first, others go last`() {
        val queue = listOf(item("1"), item("2"))
        assertThat(PendingQueue.add(queue, item("3"), playNext = true).map { it.key }).containsExactly("3", "1", "2").inOrder()
        assertThat(PendingQueue.add(queue, item("3"), playNext = false).map { it.key }).containsExactly("1", "2", "3").inOrder()
    }

    @Test
    fun `move and remove`() {
        val queue = listOf(item("1"), item("2"), item("3"))
        assertThat(PendingQueue.move(queue, "1", 2).map { it.key }).containsExactly("2", "3", "1").inOrder()
        assertThat(PendingQueue.move(queue, "3", -4).map { it.key }).containsExactly("3", "1", "2").inOrder()
        assertThat(PendingQueue.move(queue, "missing", 0)).isEqualTo(queue)
        assertThat(PendingQueue.remove(queue, "2").map { it.key }).containsExactly("1", "3").inOrder()
    }
}
