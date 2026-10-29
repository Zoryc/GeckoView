package app.snapcast.core.detection

import app.snapcast.core.model.PagePlayback
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PagePlaybackTest {
    private var now = 1_000L
    private val collector = VideoCollector(tabId = "tab-1", isPrivate = false, clock = { now })
    private val tracker = PagePlaybackTracker()

    private val page = "https://site.example/serie/dexter/1"
    private val player = "https://player.example/embed/abc"
    private val ads = "https://ads.example/slot"

    private fun network(url: String, frameUrl: String, mime: String = "application/vnd.apple.mpegurl") = MediaReport(
        url = url,
        source = "network",
        mime = mime,
        status = 200,
        resourceType = if (mime.startsWith("video/")) "media" else "xmlhttprequest",
        frameUrl = frameUrl,
        pageUrl = page,
    )

    private fun playback(
        frameUrl: String = player,
        element: Int = 1,
        src: String? = null,
        blob: Boolean = src == null,
        duration: Double? = null,
        paused: Boolean = false,
        muted: Boolean = false,
    ) = PlaybackMessage(frameUrl, element, src, blob, duration, paused, muted, at = now)

    private fun idOf(url: String) = collector.videos.single { it.url == url }.id

    @Test
    fun `the protocol reads playback messages`() {
        val message = DetectorProtocol.decode(
            """{"type":"playback","frameUrl":"$player","element":2,"blob":true,"duration":2700.5,"paused":false,"muted":true,"at":5}""",
        )
        assertThat(message).isEqualTo(PlaybackMessage(player, 2, null, true, 2700.5, paused = false, muted = true, at = 5))
    }

    @Test
    fun `a player with a plain source is that video, even once folded into its master`() {
        collector.add(network("https://cdn.example/trailer.mp4", page, mime = "video/mp4"))
        collector.add(network("https://cdn.example/hls/master.m3u8", player))
        collector.add(network("https://cdn.example/hls/720/index.m3u8", player))
        collector.onMasterParsed(idOf("https://cdn.example/hls/master.m3u8"), listOf("https://cdn.example/hls/720/index.m3u8"))

        assertThat(collector.videoPlayedBy(playback(page, src = "https://cdn.example/trailer.mp4")))
            .isEqualTo(idOf("https://cdn.example/trailer.mp4"))
        assertThat(collector.videoPlayedBy(playback(src = "https://cdn.example/hls/720/index.m3u8")))
            .isEqualTo(idOf("https://cdn.example/hls/master.m3u8"))
        assertThat(collector.videoPlayedBy(playback(src = "https://cdn.example/unknown.mp4"))).isNull()
    }

    @Test
    fun `a MediaSource player is the stream its frame asked for, of the same length`() {
        collector.add(network("https://ads.example/pre-roll.m3u8", ads))
        collector.update(idOf("https://ads.example/pre-roll.m3u8")) { it.copy(durationSec = 30.0) }
        now += 1_000
        collector.add(network("https://cdn.example/episode/master.m3u8", player))
        collector.update(idOf("https://cdn.example/episode/master.m3u8")) { it.copy(durationSec = 2701.0) }
        now += 1_000
        collector.add(network("https://cdn.example/trailer/master.m3u8", player))
        collector.update(idOf("https://cdn.example/trailer/master.m3u8")) { it.copy(durationSec = 95.0) }

        // Same frame, matching length: the episode, although the trailer was requested later.
        assertThat(collector.videoPlayedBy(playback(duration = 2700.5))).isEqualTo(idOf("https://cdn.example/episode/master.m3u8"))
        // Length not known yet: the frame's most recently requested stream.
        assertThat(collector.videoPlayedBy(playback(duration = null))).isEqualTo(idOf("https://cdn.example/trailer/master.m3u8"))
        // The ad frame's own player is the ad.
        assertThat(collector.videoPlayedBy(playback(ads, duration = 30.2))).isEqualTo(idOf("https://ads.example/pre-roll.m3u8"))
        // A frame no stream came from (a worker, an about:srcdoc frame): every stream counts.
        assertThat(collector.videoPlayedBy(playback("about:srcdoc", duration = 95.0))).isEqualTo(idOf("https://cdn.example/trailer/master.m3u8"))
    }

    @Test
    fun `a variant the player loads later makes its master the most recent stream of the frame`() {
        collector.add(network("https://cdn.example/a/master.m3u8", player))
        now += 1_000
        collector.add(network("https://cdn.example/b/master.m3u8", player))
        collector.onMasterParsed(idOf("https://cdn.example/a/master.m3u8"), listOf("https://cdn.example/a/1080.m3u8"))
        now += 1_000
        collector.add(network("https://cdn.example/a/1080.m3u8", player))

        assertThat(collector.videoPlayedBy(playback())).isEqualTo(idOf("https://cdn.example/a/master.m3u8"))
    }

    @Test
    fun `the watched element is the last unmuted one playing, else the last paused`() {
        assertThat(tracker.current).isNull()
        tracker.onPlayback(playback(page, element = 1, src = "https://cdn.example/preview.mp4", muted = true))
        tracker.onPlayback(playback(element = 1, muted = false))
        tracker.onPlayback(playback(page, element = 2, src = "https://cdn.example/hover.mp4", muted = true))
        assertThat(tracker.current?.frameUrl).isEqualTo(player)

        tracker.onPlayback(playback(element = 1, paused = true))
        assertThat(tracker.current?.element).isEqualTo(2)

        tracker.onPlayback(playback(page, element = 1, src = "https://cdn.example/preview.mp4", paused = true, muted = true))
        tracker.onPlayback(playback(page, element = 2, src = "https://cdn.example/hover.mp4", paused = true, muted = true))
        assertThat(tracker.current?.element).isEqualTo(2)
        assertThat(tracker.current?.frameUrl).isEqualTo(page)

        // An element that lost its source is forgotten.
        assertThat(tracker.onPlayback(PlaybackMessage(page, 2, paused = true))).isTrue()
        assertThat(tracker.current?.src).isEqualTo("https://cdn.example/preview.mp4")
        assertThat(tracker.onPlayback(playback(page, element = 1, src = "https://cdn.example/preview.mp4", paused = true, muted = true))).isFalse()
        tracker.clear()
        assertThat(tracker.current).isNull()
    }

    @Test
    fun `the played video comes first and marked, the others keep their order`() {
        collector.add(network("https://cdn.example/a.mp4", page, mime = "video/mp4"))
        collector.add(network("https://cdn.example/b.mp4", page, mime = "video/mp4"))
        collector.add(network("https://cdn.example/c.mp4", page, mime = "video/mp4"))
        val videos = collector.videos

        val marked = PagePlaybackTracker.mark(videos, videos[1].id, PagePlayback.PAUSED)

        assertThat(marked.map { it.url }).containsExactly("https://cdn.example/b.mp4", "https://cdn.example/a.mp4", "https://cdn.example/c.mp4").inOrder()
        assertThat(marked.map { it.pagePlayback }).containsExactly(PagePlayback.PAUSED, null, null).inOrder()
        assertThat(PagePlaybackTracker.mark(videos, "missing", PagePlayback.PLAYING)).isSameInstanceAs(videos)
    }
}
