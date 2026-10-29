package app.snapcast.detection.inspect

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.DrmInfo
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InspectionResultTest {
    private val hls = DetectedVideo(
        id = "v1",
        tabId = "tab",
        url = "https://cdn.example/master.m3u8",
        type = StreamType.HLS,
        title = "Movie",
        durationSec = 120.0,
    )

    @Test
    fun `known facts replace unknown ones`() {
        val variant = VideoVariant(url = "https://cdn.example/720.m3u8", height = 720)
        val subtitle = SubtitleTrack(id = "s1", url = "https://cdn.example/en.m3u8", language = "en", format = SubtitleFormat.VTT)
        val result = InspectionResult(
            isLive = false,
            durationSec = 5400.0,
            variants = listOf(variant),
            subtitles = listOf(subtitle),
            drm = DrmInfo("FairPlay", "HLS SAMPLE-AES key"),
            isMasterPlaylist = true,
            hlsFragmentedMp4 = true,
        )

        val updated = result.applyTo(hls)

        assertThat(updated.durationSec).isEqualTo(5400.0)
        assertThat(updated.variants).containsExactly(variant)
        assertThat(updated.subtitles).containsExactly(subtitle)
        assertThat(updated.drm).isEqualTo(DrmInfo("FairPlay", "HLS SAMPLE-AES key"))
        assertThat(updated.isMasterPlaylist).isTrue()
        assertThat(updated.hlsFragmentedMp4).isTrue()
        assertThat(updated.isLive).isFalse()
        assertThat(updated.title).isEqualTo("Movie")
        assertThat(updated.id).isEqualTo("v1")
    }

    @Test
    fun `unknown facts keep what the video already has`() {
        val video = hls.copy(
            variants = listOf(VideoVariant(url = "https://cdn.example/a.m3u8", height = 480)),
            mimeType = "application/vnd.apple.mpegurl",
            drm = DrmInfo(null, "EME request"),
            hlsFragmentedMp4 = false,
            sizeBytes = 10L,
        )

        val updated = InspectionResult(mimeType = "application/x-mpegurl", drm = DrmInfo("Widevine", "x")).applyTo(video)

        assertThat(updated).isEqualTo(video)
    }

    @Test
    fun `a manifest type the body proved replaces the label, MIME type included`() {
        val labelled = hls.copy(mimeType = "application/vnd.apple.mpegurl")

        val updated = InspectionResult(type = StreamType.DASH).applyTo(labelled)

        assertThat(updated.type).isEqualTo(StreamType.DASH)
        assertThat(updated.mimeType).isEqualTo("application/dash+xml")
        assertThat(InspectionResult(type = StreamType.HLS).applyTo(labelled)).isEqualTo(labelled)
    }

    @Test
    fun `a stream gets no file size from a probe made while it looked like a file`() {
        assertThat(InspectionResult(sizeBytes = 239L).applyTo(hls).sizeBytes).isNull()
    }

    @Test
    fun `live streams have no duration`() {
        val updated = InspectionResult(isLive = true, durationSec = 30.0).applyTo(hls)

        assertThat(updated.isLive).isTrue()
        assertThat(updated.durationSec).isNull()
    }

    @Test
    fun `invalid durations and sizes are ignored`() {
        val file = hls.copy(type = StreamType.MP4, url = "https://cdn.example/a.mp4", sizeBytes = 100L)

        val updated = InspectionResult(durationSec = Double.NaN, sizeBytes = 0L).applyTo(file)

        assertThat(updated.durationSec).isEqualTo(120.0)
        assertThat(updated.sizeBytes).isEqualTo(100L)
    }

    @Test
    fun `subtitles are merged without duplicates`() {
        val existing = SubtitleTrack(id = "a", url = "https://cdn.example/en.vtt")
        val sameUrl = SubtitleTrack(id = "b", url = "https://cdn.example/en.vtt")
        val new = SubtitleTrack(id = "c", url = "https://cdn.example/de.vtt")
        val video = hls.copy(subtitles = listOf(existing))

        val updated = InspectionResult(subtitles = listOf(sameUrl, new, new, existing)).applyTo(video)

        assertThat(updated.subtitles).containsExactly(existing, new).inOrder()
    }
}
