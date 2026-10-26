package app.snapcast.ui.local

import app.snapcast.core.media.M3uEntry
import app.snapcast.core.model.StreamType
import app.snapcast.core.util.UrlUtils
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Test

class IptvChannelsTest {
    private val news = M3uEntry(name = "News 24", url = "https://tv.example.com/news/index.m3u8", group = "News")
    private val sport = M3uEntry(name = "Sport One", url = "https://tv.example.com/live/1234", group = "Sports")
    private val weather = M3uEntry(name = "Weather", url = "https://tv.example.com/weather.ts", group = "News")
    private val misc = M3uEntry(name = "Music", url = "https://tv.example.com/music.mp4")

    @Test
    fun `groups keep first-seen order and put ungrouped channels last`() {
        val groups = IptvChannels.group(listOf(misc, news, sport, weather), query = "")
        assertThat(groups.map { it.name }).containsExactly("News", "Sports", null).inOrder()
        assertThat(groups[0].channels).containsExactly(news, weather).inOrder()
        assertThat(groups.last().key).isEmpty()
    }

    @Test
    fun `search matches channel names and groups case-insensitively`() {
        assertThat(IptvChannels.group(listOf(news, sport, weather, misc), "SPORT").flatMap { it.channels })
            .containsExactly(sport)
        assertThat(IptvChannels.group(listOf(news, sport, weather, misc), "news").flatMap { it.channels })
            .containsExactly(news, weather).inOrder()
        assertThat(IptvChannels.group(listOf(news, sport), "nothing")).isEmpty()
    }

    @Test
    fun `only http and https entries are kept`() {
        val rtmp = M3uEntry(name = "Old", url = "rtmp://tv.example.com/live")
        val playlist = IptvChannels.playlist("list.m3u", listOf(news, rtmp, sport))
        assertThat(playlist.channels).containsExactly(news, sport).inOrder()
        assertThat(playlist.skipped).isEqualTo(1)
    }

    @Test
    fun `stream types fall back to HLS for extension-less URLs`() {
        assertThat(IptvChannels.streamType(news.url)).isEqualTo(StreamType.HLS)
        assertThat(IptvChannels.streamType(sport.url)).isEqualTo(StreamType.HLS)
        assertThat(IptvChannels.streamType(weather.url)).isEqualTo(StreamType.TS)
        assertThat(IptvChannels.streamType("https://tv.example.com/play.php?id=3")).isEqualTo(StreamType.OTHER)
    }

    @Test
    fun `channels become live castable videos with VLC headers`() {
        val channel = sport.copy(
            logoUrl = "https://img.example.com/sport.png",
            attributes = mapOf("http-referrer" to "https://portal.example.com/", "http-user-agent" to "VLC/3.0"),
        )
        val video = IptvChannels.toDetectedVideo(channel, playlistName = "My TV", nowMs = 42)

        assertThat(video.id).isEqualTo(UrlUtils.stableId(channel.url))
        assertThat(video.tabId).isEqualTo(IptvChannels.TAB_ID)
        assertThat(video.type).isEqualTo(StreamType.HLS)
        assertThat(video.isLive).isTrue()
        assertThat(video.title).isEqualTo("Sport One")
        assertThat(video.pageTitle).isEqualTo("My TV")
        assertThat(video.posterUrl).isEqualTo("https://img.example.com/sport.png")
        assertThat(video.headers.referer).isEqualTo("https://portal.example.com/")
        assertThat(video.headers.userAgent).isEqualTo("VLC/3.0")
        assertThat(video.detectedAt).isEqualTo(42L)
    }

    @Test
    fun `a probed kind replaces the URL guess`() {
        val ts = IptvChannels.toDetectedVideo(sport, null, 0, ChannelKind(StreamType.TS, "video/mp2t", live = true))
        assertThat(ts.type).isEqualTo(StreamType.TS)
        assertThat(ts.mimeType).isEqualTo("video/mp2t")
        assertThat(ts.isLive).isTrue()

        val vod = IptvChannels.toDetectedVideo(sport, null, 0, ChannelKind(StreamType.MP4, null, live = false))
        assertThat(vod.type).isEqualTo(StreamType.MP4)
        assertThat(vod.isLive).isFalse()

        val guessed = IptvChannels.toDetectedVideo(sport, null, 0)
        assertThat(guessed.type).isEqualTo(StreamType.HLS)
        assertThat(guessed.mimeType).isNull()
    }

    @Test
    fun `progressive channels are not marked live`() {
        val video = IptvChannels.toDetectedVideo(misc, playlistName = null, nowMs = 0)
        assertThat(video.type).isEqualTo(StreamType.MP4)
        assertThat(video.isLive).isFalse()
        assertThat(video.headers.isEmpty).isTrue()
    }

    @Test
    fun `playlist urls need http or https`() {
        assertThat(IptvChannels.playlistUrl("example.com/list.m3u")).isEqualTo("https://example.com/list.m3u")
        assertThat(IptvChannels.playlistUrl(" http://192.168.1.5:8000/tv.m3u8 ")).isEqualTo("http://192.168.1.5:8000/tv.m3u8")
        assertThat(IptvChannels.playlistUrl("ftp://example.com/list.m3u")).isNull()
        assertThat(IptvChannels.playlistUrl("two words")).isNull()
        assertThat(IptvChannels.playlistUrl("")).isNull()
    }

    @Test
    fun `playlist names come from the file name or the host`() {
        assertThat(IptvChannels.nameFromUrl("https://example.com/lists/world%20tv.m3u")).isEqualTo("world tv.m3u")
        assertThat(IptvChannels.nameFromUrl("https://iptv.example.com/")).isEqualTo("iptv.example.com")
    }

    @Test
    fun `bounded reads stop at the limit`() {
        val data = ByteArray(100) { it.toByte() }
        assertThat(BoundedReader.readAtMost(ByteArrayInputStream(data), 100)).isEqualTo(data)
        assertThat(BoundedReader.readAtMost(ByteArrayInputStream(data), 99)).isNull()
        assertThat(BoundedReader.readAtMost(ByteArrayInputStream(ByteArray(0)), 10)).isEmpty()
    }
}
