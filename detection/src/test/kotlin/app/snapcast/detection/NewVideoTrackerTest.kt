package app.snapcast.detection

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NewVideoTrackerTest {
    private fun video(id: String, tabId: String = "t1") =
        DetectedVideo(id = id, tabId = tabId, url = "https://cdn.example/$id.mp4", type = StreamType.MP4)

    @Test
    fun `each visible video is announced once`() {
        val tracker = NewVideoTracker()
        val a = video("a")
        val b = video("b")

        assertThat(tracker.newlyVisible(mapOf("t1" to listOf(a)))).containsExactly(a)
        assertThat(tracker.newlyVisible(mapOf("t1" to listOf(a, b)))).containsExactly(b)
        assertThat(tracker.newlyVisible(mapOf("t1" to listOf(a.copy(title = "updated"), b)))).isEmpty()
    }

    @Test
    fun `a video hidden by the filters and shown again is not announced twice`() {
        val tracker = NewVideoTracker()
        val a = video("a")
        tracker.newlyVisible(mapOf("t1" to listOf(a)))
        tracker.newlyVisible(mapOf("t1" to emptyList()))
        assertThat(tracker.newlyVisible(mapOf("t1" to listOf(a)))).isEmpty()
    }

    @Test
    fun `tabs are tracked separately and reset with a new document`() {
        val tracker = NewVideoTracker()
        val a = video("a")
        val otherTab = video("a", tabId = "t2")
        assertThat(tracker.newlyVisible(mapOf("t1" to listOf(a), "t2" to listOf(otherTab)))).containsExactly(a, otherTab).inOrder()

        tracker.resetTab("t1")
        assertThat(tracker.newlyVisible(mapOf("t1" to listOf(a), "t2" to listOf(otherTab)))).containsExactly(a)
    }
}
