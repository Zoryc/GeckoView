package app.snapcast.core.media

import app.snapcast.core.media.MediaClassifier.LARGE_FILE_BYTES
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleFormat
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class MediaClassifierTest {
    private fun stream(type: StreamType) = MediaKind.Stream(type)

    @Test
    fun `classifies by URL extension when there is no content type`() {
        assertThat(MediaClassifier.classify("https://e.com/live/master.m3u8")).isEqualTo(stream(StreamType.HLS))
        assertThat(MediaClassifier.classify("https://e.com/dash/manifest.mpd")).isEqualTo(stream(StreamType.DASH))
        assertThat(MediaClassifier.classify("https://e.com/movie.mp4")).isEqualTo(stream(StreamType.MP4))
        assertThat(MediaClassifier.classify("https://e.com/movie.m4v")).isEqualTo(stream(StreamType.MP4))
        assertThat(MediaClassifier.classify("https://e.com/clip.webm")).isEqualTo(stream(StreamType.WEBM))
        assertThat(MediaClassifier.classify("https://e.com/film.mkv")).isEqualTo(stream(StreamType.MKV))
        assertThat(MediaClassifier.classify("https://e.com/film.mov")).isEqualTo(stream(StreamType.OTHER))
        assertThat(MediaClassifier.classify("https://e.com/page.html")).isEqualTo(MediaKind.NotMedia)
        assertThat(MediaClassifier.classify("https://e.com/")).isEqualTo(MediaKind.NotMedia)
    }

    @Test
    fun `extension match is case-insensitive and ignores query and fragment`() {
        assertThat(MediaClassifier.classify("https://e.com/Master.M3U8?token=a.mp4")).isEqualTo(stream(StreamType.HLS))
        assertThat(MediaClassifier.classify("https://e.com/MOVIE.MP4#t=10")).isEqualTo(stream(StreamType.MP4))
        assertThat(MediaClassifier.classify("https://e.com/video?file=movie.mp4")).isEqualTo(MediaKind.NotMedia)
    }

    @Test
    fun `recognises query hints for manifests`() {
        assertThat(MediaClassifier.classify("https://e.com/play?id=5&format=m3u8")).isEqualTo(stream(StreamType.HLS))
        assertThat(MediaClassifier.classify("https://e.com/v.ism/manifest(format=m3u8-aapl)")).isEqualTo(stream(StreamType.HLS))
        assertThat(MediaClassifier.classify("https://e.com/v.ism/manifest(format=mpd-time-csf)")).isEqualTo(stream(StreamType.DASH))
        assertThat(MediaClassifier.classify("https://e.com/get?format=mpd")).isEqualTo(stream(StreamType.DASH))
        assertThat(MediaClassifier.typeFromUrl("https://e.com/get?FORMAT=M3U8")).isEqualTo(StreamType.HLS)
    }

    @Test
    fun `specific content type wins over the URL`() {
        assertThat(MediaClassifier.classify("https://e.com/playlist.php", "application/vnd.apple.mpegurl"))
            .isEqualTo(stream(StreamType.HLS))
        assertThat(MediaClassifier.classify("https://e.com/stream", "application/x-mpegURL; charset=UTF-8"))
            .isEqualTo(stream(StreamType.HLS))
        assertThat(MediaClassifier.classify("https://e.com/a", "audio/mpegurl")).isEqualTo(stream(StreamType.HLS))
        assertThat(MediaClassifier.classify("https://e.com/a", "audio/x-mpegurl")).isEqualTo(stream(StreamType.HLS))
        assertThat(MediaClassifier.classify("https://e.com/m", "application/dash+xml")).isEqualTo(stream(StreamType.DASH))
        assertThat(MediaClassifier.classify("https://e.com/movie.mp4", "application/vnd.apple.mpegurl"))
            .isEqualTo(stream(StreamType.HLS))
        assertThat(MediaClassifier.classify("https://e.com/file", "video/mp4")).isEqualTo(stream(StreamType.MP4))
        assertThat(MediaClassifier.classify("https://e.com/file", "VIDEO/WEBM")).isEqualTo(stream(StreamType.WEBM))
        assertThat(MediaClassifier.classify("https://e.com/file", "video/x-matroska")).isEqualTo(stream(StreamType.MKV))
        assertThat(MediaClassifier.classify("https://e.com/file", "video/quicktime")).isEqualTo(stream(StreamType.OTHER))
        assertThat(MediaClassifier.classify("https://e.com/file.m3u8", "text/html")).isEqualTo(MediaKind.NotMedia)
    }

    @Test
    fun `generic or misconfigured content types fall back to the URL`() {
        assertThat(MediaClassifier.classify("https://e.com/master.m3u8", "application/octet-stream"))
            .isEqualTo(stream(StreamType.HLS))
        assertThat(MediaClassifier.classify("https://e.com/master.m3u8", "binary/octet-stream"))
            .isEqualTo(stream(StreamType.HLS))
        assertThat(MediaClassifier.classify("https://e.com/master.m3u8", "text/plain")).isEqualTo(stream(StreamType.HLS))
        assertThat(MediaClassifier.classify("https://e.com/manifest.mpd", "application/xml")).isEqualTo(stream(StreamType.DASH))
        assertThat(MediaClassifier.classify("https://e.com/movie.mp4", "")).isEqualTo(stream(StreamType.MP4))
        assertThat(MediaClassifier.classify("https://e.com/subs.vtt", "text/plain")).isEqualTo(MediaKind.Subtitle(SubtitleFormat.VTT))
        assertThat(MediaClassifier.classify("https://e.com/blob", "application/octet-stream")).isEqualTo(MediaKind.NotMedia)
    }

    @Test
    fun `mpeg-ts is a segment unless large`() {
        assertThat(MediaClassifier.classify("https://e.com/x", "video/mp2t")).isEqualTo(MediaKind.Segment)
        assertThat(MediaClassifier.classify("https://e.com/x", "video/MP2T", contentLength = LARGE_FILE_BYTES))
            .isEqualTo(stream(StreamType.TS))
        assertThat(MediaClassifier.classify("https://e.com/seg-1.ts")).isEqualTo(MediaKind.Segment)
        assertThat(MediaClassifier.classify("https://e.com/movie.TS", contentLength = 2 * LARGE_FILE_BYTES))
            .isEqualTo(stream(StreamType.TS))
        assertThat(MediaClassifier.classify("https://e.com/movie.ts", contentLength = LARGE_FILE_BYTES - 1))
            .isEqualTo(MediaKind.Segment)
    }

    @Test
    fun `adaptive chunk extensions are segments`() {
        listOf("v/chunk.m4s", "a.m4f", "x.cmfv", "x.cmfa", "audio/0001.aac", "seg.fmp4", "SEG.M4S").forEach {
            assertThat(MediaClassifier.classify("https://e.com/$it")).isEqualTo(MediaKind.Segment)
        }
        assertThat(MediaClassifier.classify("https://e.com/dash/init.mp4")).isEqualTo(MediaKind.Segment)
        assertThat(MediaClassifier.classify("https://e.com/dash/video_init_1080.m4s")).isEqualTo(MediaKind.Segment)
        assertThat(MediaClassifier.classify("https://e.com/x", "video/iso.segment")).isEqualTo(MediaKind.Segment)
    }

    @Test
    fun `segment-looking progressive URLs are segments unless large`() {
        assertThat(MediaClassifier.classify("https://e.com/hls/segment12.mp4")).isEqualTo(MediaKind.Segment)
        assertThat(MediaClassifier.classify("https://e.com/v/frag-3.mp4", "video/mp4")).isEqualTo(MediaKind.Segment)
        assertThat(MediaClassifier.classify("https://e.com/movie.mp4?range=0-50000")).isEqualTo(MediaKind.Segment)
        assertThat(MediaClassifier.classify("https://e.com/movie.mp4?bytes=100-200", "video/mp4")).isEqualTo(MediaKind.Segment)
        assertThat(MediaClassifier.classify("https://e.com/hls/segment12.mp4", contentLength = LARGE_FILE_BYTES))
            .isEqualTo(stream(StreamType.MP4))
    }

    @Test
    fun `xhr mp4 and webm are MSE segments unless at least 20 MB`() {
        val twentyMb = 20L * 1024 * 1024
        assertThat(MediaClassifier.classify("https://e.com/v.mp4", "video/mp4", "xmlhttprequest", 1_000_000))
            .isEqualTo(MediaKind.Segment)
        assertThat(MediaClassifier.classify("https://e.com/v.webm", null, "xmlhttprequest", null)).isEqualTo(MediaKind.Segment)
        assertThat(MediaClassifier.classify("https://e.com/v.mp4", "video/mp4", "xmlhttprequest", twentyMb))
            .isEqualTo(stream(StreamType.MP4))
        assertThat(MediaClassifier.classify("https://e.com/v.mp4", "video/mp4", "media", 1_000_000)).isEqualTo(stream(StreamType.MP4))
        assertThat(MediaClassifier.classify("https://e.com/v.m3u8", null, "xmlhttprequest", 500)).isEqualTo(stream(StreamType.HLS))
        assertThat(MediaClassifier.classify("https://e.com/v.mkv", null, "xmlhttprequest", 500)).isEqualTo(stream(StreamType.MKV))
    }

    @Test
    fun `audio images and documents are not media`() {
        assertThat(MediaClassifier.classify("https://e.com/song.mp3", "audio/mpeg")).isEqualTo(MediaKind.NotMedia)
        assertThat(MediaClassifier.classify("https://e.com/a.mp4", "audio/mp4")).isEqualTo(MediaKind.NotMedia)
        assertThat(MediaClassifier.classify("https://e.com/p.jpg", "image/jpeg")).isEqualTo(MediaKind.NotMedia)
        assertThat(MediaClassifier.classify("https://e.com/movie.mp4", "image/png")).isEqualTo(MediaKind.NotMedia)
        assertThat(MediaClassifier.classify("https://e.com/", "text/html", "main_frame")).isEqualTo(MediaKind.NotMedia)
        assertThat(MediaClassifier.classify("https://e.com/app.js", "application/javascript")).isEqualTo(MediaKind.NotMedia)
    }

    @Test
    fun `unknown video subtypes are generic streams`() {
        assertThat(MediaClassifier.classify("https://e.com/stream", "video/ogg")).isEqualTo(stream(StreamType.OTHER))
        assertThat(MediaClassifier.classify("https://e.com/movie.mp4", "video/x-unknown")).isEqualTo(stream(StreamType.MP4))
    }

    @Test
    fun `subtitles by content type and URL`() {
        assertThat(MediaClassifier.classify("https://e.com/s", "text/vtt")).isEqualTo(MediaKind.Subtitle(SubtitleFormat.VTT))
        assertThat(MediaClassifier.classify("https://e.com/s", "application/x-subrip")).isEqualTo(MediaKind.Subtitle(SubtitleFormat.SRT))
        assertThat(MediaClassifier.classify("https://e.com/s", "text/srt")).isEqualTo(MediaKind.Subtitle(SubtitleFormat.SRT))
        assertThat(MediaClassifier.classify("https://e.com/s", "application/ttml+xml")).isEqualTo(MediaKind.Subtitle(SubtitleFormat.TTML))
        assertThat(MediaClassifier.classify("https://e.com/en.VTT")).isEqualTo(MediaKind.Subtitle(SubtitleFormat.VTT))
        assertThat(MediaClassifier.classify("https://e.com/en.webvtt")).isEqualTo(MediaKind.Subtitle(SubtitleFormat.VTT))
        assertThat(MediaClassifier.classify("https://e.com/en.srt?x=1")).isEqualTo(MediaKind.Subtitle(SubtitleFormat.SRT))
        assertThat(MediaClassifier.classify("https://e.com/en.ttml")).isEqualTo(MediaKind.Subtitle(SubtitleFormat.TTML))
        assertThat(MediaClassifier.classify("https://e.com/en.dfxp")).isEqualTo(MediaKind.Subtitle(SubtitleFormat.TTML))
    }

    @Test
    fun `typeFromContentType ignores parameters and case`() {
        assertThat(MediaClassifier.typeFromContentType("Application/Vnd.Apple.MpegURL; charset=utf-8")).isEqualTo(StreamType.HLS)
        assertThat(MediaClassifier.typeFromContentType("application/dash+xml;profiles=x")).isEqualTo(StreamType.DASH)
        assertThat(MediaClassifier.typeFromContentType("video/mp4; codecs=\"avc1\"")).isEqualTo(StreamType.MP4)
        assertThat(MediaClassifier.typeFromContentType("video/mp2t")).isEqualTo(StreamType.TS)
        assertThat(MediaClassifier.typeFromContentType("application/octet-stream")).isNull()
        assertThat(MediaClassifier.typeFromContentType(null)).isNull()
        assertThat(MediaClassifier.typeFromContentType("  ")).isNull()
    }

    @Test
    fun `typeFromUrl maps extensions`() {
        assertThat(MediaClassifier.typeFromUrl("https://e.com/a.ts")).isEqualTo(StreamType.TS)
        assertThat(MediaClassifier.typeFromUrl("https://e.com/a.MKV")).isEqualTo(StreamType.MKV)
        assertThat(MediaClassifier.typeFromUrl("https://e.com/a.mov")).isEqualTo(StreamType.OTHER)
        assertThat(MediaClassifier.typeFromUrl("https://e.com/a")).isNull()
    }

    @Test
    fun `subtitle format helpers`() {
        assertThat(MediaClassifier.subtitleFormatFromUrl("https://e.com/a.SRT")).isEqualTo(SubtitleFormat.SRT)
        assertThat(MediaClassifier.subtitleFormatFromUrl("https://e.com/a.mp4")).isNull()
        assertThat(MediaClassifier.subtitleFormatFromContentType("text/vtt;charset=utf-8")).isEqualTo(SubtitleFormat.VTT)
        assertThat(MediaClassifier.subtitleFormatFromContentType("text/plain")).isNull()
    }

    @Test
    fun `isSegmentUrl patterns`() {
        val segments = listOf(
            "https://e.com/hls/segment1.ts",
            "https://e.com/hls/seg-12-v1-a1.ts",
            "https://e.com/a/chunk_00012.m4s",
            "https://e.com/a/Frag5",
            "https://e.com/a/fragment-3.mp4",
            "https://e.com/hds/Seg1-Frag12",
            "https://e.com/720/00012.ts",
            "https://e.com/subs/0001.vtt",
            "https://e.com/subs/fileSequence12.webvtt",
            "https://e.com/dash/init.mp4",
            "https://r3.googlevideo.com/videoplayback?expire=1&itag=22",
            "https://e.com/movie.mp4?range=0-1000",
            "https://e.com/movie.mp4?a=1&Bytes=0-1000",
            "https://e.com/v.m4s",
        )
        segments.forEach { assertWithMessage(it).that(MediaClassifier.isSegmentUrl(it)).isTrue() }

        val standalone = listOf(
            "https://e.com/movie.mp4",
            "https://e.com/videos/12345.mp4",
            "https://e.com/hls/chunklist_w123.m3u8",
            "https://e.com/initial-trailer.mp4",
            "https://e.com/subs/en.vtt",
            "https://e.com/videoplayback",
            "https://e.com/master.m3u8?arrange=1",
            "blob:https://e.com/1234-5678",
        )
        standalone.forEach { assertWithMessage(it).that(MediaClassifier.isSegmentUrl(it)).isFalse() }
    }

    @Test
    fun `sniffPlaylist detects HLS and DASH bodies`() {
        assertThat(MediaClassifier.sniffPlaylist("#EXTM3U\n#EXT-X-VERSION:3")).isEqualTo(StreamType.HLS)
        assertThat(MediaClassifier.sniffPlaylist("﻿  #EXTM3U")).isEqualTo(StreamType.HLS)
        assertThat(MediaClassifier.sniffPlaylist("<?xml version=\"1.0\"?>\n<!-- c -->\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">"))
            .isEqualTo(StreamType.DASH)
        assertThat(MediaClassifier.sniffPlaylist("﻿<MPD>")).isEqualTo(StreamType.DASH)
        assertThat(MediaClassifier.sniffPlaylist("<mpd:MPD xmlns:mpd=\"x\">")).isEqualTo(StreamType.DASH)
        assertThat(MediaClassifier.sniffPlaylist("<MPD")).isEqualTo(StreamType.DASH)
        assertThat(MediaClassifier.sniffPlaylist("<MPDX>")).isNull()
        assertThat(MediaClassifier.sniffPlaylist("<html><body>")).isNull()
        assertThat(MediaClassifier.sniffPlaylist("")).isNull()
    }
}
