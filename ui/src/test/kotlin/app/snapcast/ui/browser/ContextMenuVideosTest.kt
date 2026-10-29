package app.snapcast.ui.browser

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.DetectionSource
import app.snapcast.core.model.MediaHeaders
import app.snapcast.core.model.PagePlayback
import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContextMenuVideosTest {
    private val page = ContextMenuVideos.Page(
        tabId = "tab-1",
        url = "https://videos.example.com/watch/42",
        title = "Watch 42",
        isPrivate = false,
    )

    private fun detected(url: String, type: StreamType, id: String = url) = DetectedVideo(
        id = id,
        tabId = "tab-1",
        url = url,
        type = type,
        headers = MediaHeaders(referer = "https://videos.example.com/", cookie = "sid=1"),
    )

    @Test
    fun `missing or blank source gives nothing`() {
        assertThat(ContextMenuVideos.resolve(null, null, page, emptyList(), 0)).isNull()
        assertThat(ContextMenuVideos.resolve("  ", null, page, emptyList(), 0)).isNull()
    }

    @Test
    fun `already detected video is reused with its headers`() {
        val known = detected("https://cdn.example.com/movie.mp4", StreamType.MP4)
        val result = ContextMenuVideos.resolve(known.url, "Title", page, listOf(known), 5)
        assertThat(result).isSameInstanceAs(known)
    }

    @Test
    fun `blob source maps to the most recent adaptive stream`() {
        val mp4 = detected("https://cdn.example.com/ad.mp4", StreamType.MP4)
        val hls = detected("https://cdn.example.com/master.m3u8", StreamType.HLS)
        val other = detected("https://cdn.example.com/clip.webm", StreamType.WEBM)
        val result = ContextMenuVideos.resolve("blob:https://videos.example.com/1234", null, page, listOf(mp4, hls, other), 0)
        assertThat(result).isSameInstanceAs(hls)
    }

    @Test
    fun `blob source maps to the stream the page plays, which the list shows first`() {
        val episode = detected("https://cdn.example.com/episode/master.m3u8", StreamType.HLS).copy(pagePlayback = PagePlayback.PAUSED)
        val trailer = detected("https://cdn.example.com/trailer/master.m3u8", StreamType.HLS)
        val result = ContextMenuVideos.resolve("blob:https://videos.example.com/1234", null, page, listOf(episode, trailer), 0)
        assertThat(result).isSameInstanceAs(episode)
    }

    @Test
    fun `blob source without detections gives nothing`() {
        assertThat(ContextMenuVideos.resolve("blob:https://videos.example.com/1234", null, page, emptyList(), 0)).isNull()
    }

    @Test
    fun `non http sources are not castable`() {
        assertThat(ContextMenuVideos.resolve("data:video/mp4;base64,AAAA", null, page, emptyList(), 0)).isNull()
    }

    @Test
    fun `unknown http source becomes a DOM video with the page as referer`() {
        val src = "https://media.example.net/stream/index.m3u8"
        val result = ContextMenuVideos.resolve(src, "Element title", page, emptyList(), 1234L)
        checkNotNull(result)
        assertThat(result.url).isEqualTo(src)
        assertThat(result.tabId).isEqualTo("tab-1")
        assertThat(result.type).isEqualTo(StreamType.HLS)
        assertThat(result.headers.referer).isEqualTo(page.url)
        assertThat(result.pageUrl).isEqualTo(page.url)
        assertThat(result.pageTitle).isEqualTo("Watch 42")
        assertThat(result.title).isEqualTo("Element title")
        assertThat(result.source).isEqualTo(DetectionSource.DOM)
        assertThat(result.detectedAt).isEqualTo(1234L)
        assertThat(result.id).isNotEmpty()
    }

    @Test
    fun `page without http url gives no referer`() {
        val homePage = page.copy(url = null)
        val result = ContextMenuVideos.resolve("https://media.example.net/a.mp4", null, homePage, emptyList(), 0)
        checkNotNull(result)
        assertThat(result.headers.referer).isNull()
        assertThat(result.title).isNull()
    }
}
