package app.snapcast.detection

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.DrmInfo
import app.snapcast.core.model.MediaHeaders
import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InspectionTrackerTest {
    private val hls = DetectedVideo(id = "h", tabId = "t", url = "https://cdn.example/a.m3u8", type = StreamType.HLS)
    private val mp4 = DetectedVideo(id = "m", tabId = "t", url = "https://cdn.example/a.mp4", type = StreamType.MP4)

    @Test
    fun `adaptive streams and progressive files of unknown size need inspection`() {
        assertThat(InspectionTracker.needsInspection(hls)).isTrue()
        assertThat(InspectionTracker.needsInspection(hls.copy(type = StreamType.DASH))).isTrue()
        assertThat(InspectionTracker.needsInspection(mp4)).isTrue()
        assertThat(InspectionTracker.needsInspection(mp4.copy(sizeBytes = 1000L))).isFalse()
        assertThat(InspectionTracker.needsInspection(hls.copy(drm = DrmInfo(null, "EME request")))).isFalse()
        assertThat(InspectionTracker.needsInspection(mp4.copy(url = "ftp://cdn.example/a.mp4"))).isFalse()
    }

    @Test
    fun `a video is inspected once`() {
        val tracker = InspectionTracker()
        assertThat(tracker.shouldStart(hls)).isTrue()
        assertThat(tracker.shouldStart(hls)).isFalse()
        tracker.finished(hls.id, succeeded = true)
        assertThat(tracker.shouldStart(hls.copy(headers = MediaHeaders(cookie = "a=1")))).isFalse()
    }

    @Test
    fun `a failed inspection is retried once when the request headers changed`() {
        val tracker = InspectionTracker()
        assertThat(tracker.shouldStart(hls)).isTrue()
        tracker.finished(hls.id, succeeded = false)
        assertThat(tracker.shouldStart(hls)).isFalse()

        val withCookie = hls.copy(headers = MediaHeaders(cookie = "a=1"))
        assertThat(tracker.shouldStart(withCookie)).isTrue()
        assertThat(tracker.shouldStart(withCookie.copy(cookiesByHost = mapOf("x" to "y")))).isFalse()
        tracker.finished(hls.id, succeeded = false)
        assertThat(tracker.shouldStart(withCookie.copy(cookiesByHost = mapOf("x" to "y")))).isFalse()
    }

    @Test
    fun `clear forgets every attempt`() {
        val tracker = InspectionTracker()
        tracker.shouldStart(mp4)
        tracker.clear()
        assertThat(tracker.shouldStart(mp4)).isTrue()
        tracker.finished("unknown", succeeded = true)
    }
}
