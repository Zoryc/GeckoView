package app.snapcast.core.detection

import app.snapcast.core.model.PagePlayback
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PagePlaybackTest {
    private var now = 1_000L
    private val collector = VideoCollector(tabId = "tab-1", isPrivate = false, clock = { now })
    private val tracker = PagePlaybackTracker(clock = { now })

    private val page = "https://site.example/serie/dexter/1"
    private val player = "https://player.example/embed/abc"
    private val ads = "https://ads.example/slot"

    private fun network(url: String, frameUrl: String, mime: String = "application/vnd.apple.mpegurl", timestamp: Long = now) = MediaReport(
        url = url,
        source = "network",
        mime = mime,
        status = 200,
        resourceType = if (mime.startsWith("video/")) "media" else "xmlhttprequest",
        frameUrl = frameUrl,
        pageUrl = page,
        timestamp = timestamp,
    )

    private fun playback(
        frameUrl: String = player,
        element: Int = 1,
        src: String? = null,
        blob: Boolean = src == null,
        duration: Double? = null,
        paused: Boolean = false,
        muted: Boolean = false,
        frame: String? = null,
    ) = PlaybackMessage(frameUrl, element, src, blob, duration, paused, muted, at = now, frame = frame)

    private fun idOf(url: String) = collector.videos.single { it.url == url }.id

    @Test
    fun `the protocol reads playback messages`() {
        val message = DetectorProtocol.decode(
            """{"type":"playback","frame":"k1","frameUrl":"$player","element":2,"blob":true,"duration":2700.5,"paused":false,"muted":true,"at":5}""",
        )
        assertThat(message).isEqualTo(PlaybackMessage(player, 2, null, true, 2700.5, paused = false, muted = true, at = 5, frame = "k1"))
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
        // A length no stream has: none of them, rather than a wrong one.
        assertThat(collector.videoPlayedBy(playback(duration = 600.0))).isNull()
    }

    @Test
    fun `the closest length wins, and a stream of unknown length beats one that contradicts the player`() {
        collector.add(network("https://cdn.example/e1/master.m3u8", player))
        collector.update(idOf("https://cdn.example/e1/master.m3u8")) { it.copy(durationSec = 2690.0) }
        now += 1_000
        collector.add(network("https://cdn.example/e2/master.m3u8", player))
        collector.update(idOf("https://cdn.example/e2/master.m3u8")) { it.copy(durationSec = 2712.0) }
        assertThat(collector.videoPlayedBy(playback(duration = 2692.0))).isEqualTo(idOf("https://cdn.example/e1/master.m3u8"))

        now += 1_000
        collector.add(network("https://cdn.example/new/master.m3u8", player))
        assertThat(collector.videoPlayedBy(playback(duration = 1500.0))).isEqualTo(idOf("https://cdn.example/new/master.m3u8"))
    }

    @Test
    fun `a replay of old reports does not make an old stream the most recent`() {
        collector.add(network("https://cdn.example/old/master.m3u8", player, timestamp = 1_000))
        collector.add(network("https://cdn.example/new/master.m3u8", player, timestamp = 2_000))
        now = 60_000
        // A rescan replays everything the page reported, in the order it was found.
        collector.add(network("https://cdn.example/old/master.m3u8", player, timestamp = 1_000))
        collector.add(network("https://cdn.example/new/master.m3u8", player, timestamp = 2_000))
        collector.add(network("https://cdn.example/old/master.m3u8", player, timestamp = 1_000))

        assertThat(collector.videoPlayedBy(playback())).isEqualTo(idOf("https://cdn.example/new/master.m3u8"))
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
    fun `the watched element is the one with sound, playing or paused, before muted ones`() {
        assertThat(tracker.current).isNull()
        tracker.onPlayback(playback(page, element = 1, src = "https://cdn.example/preview.mp4", muted = true))
        tracker.onPlayback(playback(element = 1, muted = false))
        tracker.onPlayback(playback(page, element = 2, src = "https://cdn.example/hover.mp4", muted = true))
        assertThat(tracker.current?.frameUrl).isEqualTo(player)

        // Paused before opening the list, while a muted ad keeps playing: still the episode.
        tracker.onPlayback(playback(element = 1, paused = true))
        assertThat(tracker.current?.frameUrl).isEqualTo(player)
        // Unless it played to the end: then the muted video still playing.
        tracker.onPlayback(PlaybackMessage(player, 1, null, true, null, paused = true, at = now, ended = true))
        assertThat(tracker.current?.element).isEqualTo(2)

        tracker.onPlayback(playback(page, element = 1, src = "https://cdn.example/preview.mp4", paused = true, muted = true))
        tracker.onPlayback(playback(page, element = 2, src = "https://cdn.example/hover.mp4", paused = true, muted = true))
        assertThat(tracker.current?.element).isEqualTo(2)
        assertThat(tracker.current?.frameUrl).isEqualTo(page)

        // An element that lost its source is forgotten.
        assertThat(tracker.onPlayback(PlaybackMessage(page, 2, paused = true, at = now))).isTrue()
        assertThat(tracker.current?.src).isEqualTo("https://cdn.example/preview.mp4")
        assertThat(tracker.onPlayback(playback(page, element = 1, src = "https://cdn.example/preview.mp4", paused = true, muted = true))).isFalse()
        tracker.clear()
        assertThat(tracker.current).isNull()
    }

    @Test
    fun `a playing element that stopped sending heartbeats is gone, a heartbeat keeps it`() {
        val episode = playback(frame = "player", duration = 2700.0)
        tracker.onPlayback(episode)
        now += 30_000
        assertThat(tracker.onPlayback(episode)).isFalse()
        now += 30_000
        assertThat(tracker.current).isEqualTo(episode)

        // Its frame was removed without a pause event: after the stale delay something paused earlier wins.
        tracker.onPlayback(playback(page, element = 3, src = "https://cdn.example/trailer.mp4", paused = true, frame = "top"))
        now += PagePlaybackTracker.STALE_MS + 1
        assertThat(tracker.current?.src).isEqualTo("https://cdn.example/trailer.mp4")
    }

    @Test
    fun `elements are told apart by their document, whatever its URL says`() {
        tracker.onPlayback(playback(element = 1, frame = "a"))
        tracker.onPlayback(playback(element = 1, frame = "b", muted = true))
        // pushState in document "a", then its element stops: the same element under a new URL, not a second one
        // (the old URL's entry would otherwise still be "playing with sound" and win).
        tracker.onPlayback(playback("$player?ep=2", element = 1, frame = "a", muted = true, paused = true))

        assertThat(tracker.current?.frame).isEqualTo("b")
        tracker.onPlayback(playback(element = 1, frame = "b", muted = true, paused = true))
        assertThat(tracker.current?.frame).isEqualTo("b")
        assertThat(tracker.current?.frameUrl).isEqualTo(player)
    }

    @Test
    fun `a long playing episode outlives twenty ads`() {
        tracker.onPlayback(playback(element = 1, frame = "player", duration = 2700.0))
        for (i in 2..30) tracker.onPlayback(playback(ads, element = i, src = "https://ads.example/$i.mp4", paused = true, frame = "ads"))
        assertThat(tracker.current?.element).isEqualTo(1)
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
