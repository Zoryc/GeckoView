package app.snapcast.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uParserTest {
    @Test
    fun `parses extended M3U with attributes and groups`() {
        val text = """
            #EXTM3U x-tvg-url="https://epg.example.com/guide.xml"
            #EXTINF:-1 tvg-id="news.1" tvg-name="News One" tvg-logo="https://img.example.com/news1.png" group-title="News, Local",News One HD
            https://iptv.example.com/live/news1.m3u8
            #EXTINF:-1 tvg-id="sport" group-title="Sports",Sport "Extra", Live
            #EXTVLCOPT:http-referrer=https://iptv.example.com/
            #EXTVLCOPT:http-user-agent=Mozilla/5.0
            http://iptv.example.com/live/sport.ts
            #EXTINF:3600,Movie Night
            #EXTGRP:Movies
            https://vod.example.com/movie.mp4
        """.trimIndent()
        val entries = M3uParser.parse(text)
        assertThat(entries).hasSize(3)

        val news = entries[0]
        assertThat(news.name).isEqualTo("News One HD")
        assertThat(news.url).isEqualTo("https://iptv.example.com/live/news1.m3u8")
        assertThat(news.logoUrl).isEqualTo("https://img.example.com/news1.png")
        assertThat(news.group).isEqualTo("News, Local")
        assertThat(news.durationSec).isNull()
        assertThat(news.attributes).containsEntry("tvg-id", "news.1")
        assertThat(news.attributes).containsEntry("tvg-name", "News One")

        val sport = entries[1]
        assertThat(sport.name).isEqualTo("Sport \"Extra\", Live")
        assertThat(sport.group).isEqualTo("Sports")
        assertThat(sport.logoUrl).isNull()
        assertThat(sport.attributes).containsEntry("http-referrer", "https://iptv.example.com/")
        assertThat(sport.attributes).containsEntry("http-user-agent", "Mozilla/5.0")

        val movie = entries[2]
        assertThat(movie.name).isEqualTo("Movie Night")
        assertThat(movie.durationSec).isEqualTo(3600.0)
        assertThat(movie.group).isEqualTo("Movies")
        assertThat(movie.attributes).isEmpty()
    }

    @Test
    fun `resolves relative entries and logos against the base URL`() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-logo=\"logos/a.png\",A\nchannels/a.m3u8\n#EXTINF:-1,B\n/abs/b.ts\nhttp://other.example.com/c.mp4\n"
        val entries = M3uParser.parse(text, "https://lists.example.com/iptv/list.m3u")
        assertThat(entries.map { it.url }).containsExactly(
            "https://lists.example.com/iptv/channels/a.m3u8",
            "https://lists.example.com/abs/b.ts",
            "http://other.example.com/c.mp4",
        ).inOrder()
        assertThat(entries[0].logoUrl).isEqualTo("https://lists.example.com/iptv/logos/a.png")
        assertThat(entries[2].name).isEqualTo("c.mp4")
    }

    @Test
    fun `parses simple M3U without header`() {
        val text = "﻿# my list\r\nhttps://e.com/one.mp4\r\n\r\nhttps://e.com/Two%20Words.mkv\r\n"
        val entries = M3uParser.parse(text)
        assertThat(entries.map { it.name }).containsExactly("one.mp4", "Two Words.mkv").inOrder()
        assertThat(entries.map { it.url }).containsExactly("https://e.com/one.mp4", "https://e.com/Two%20Words.mkv").inOrder()
    }

    @Test
    fun `relative entries stay relative without a base URL`() {
        val entries = M3uParser.parse("#EXTM3U\n#EXTINF:5,Clip\nclips/a.mp4\n")
        assertThat(entries.single().url).isEqualTo("clips/a.mp4")
        assertThat(entries.single().durationSec).isEqualTo(5.0)
    }

    @Test
    fun `name falls back to tvg-name then file name`() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-name=\"Fallback\",\nhttps://e.com/x.ts\n#EXTINF:-1,\nhttps://e.com/live/\n"
        val entries = M3uParser.parse(text)
        assertThat(entries[0].name).isEqualTo("Fallback")
        assertThat(entries[1].name).isEqualTo("https://e.com/live/")
    }

    @Test
    fun `metadata does not leak into the next entry`() {
        val text = "#EXTM3U\n#EXTINF:-1 group-title=\"G\",First\n#EXTGRP:Other\nhttps://e.com/1.ts\nhttps://e.com/2.ts\n"
        val entries = M3uParser.parse(text)
        assertThat(entries[0].group).isEqualTo("G")
        assertThat(entries[1].name).isEqualTo("2.ts")
        assertThat(entries[1].group).isNull()
        assertThat(entries[1].attributes).isEmpty()
    }

    @Test
    fun `unquoted and single-quoted attribute values`() {
        val entries = M3uParser.parse("#EXTM3U\n#EXTINF:-1 tvg-id=abc tvg-logo='https://e.com/l.png',Name\nhttps://e.com/s.m3u8\n")
        assertThat(entries.single().attributes).containsEntry("tvg-id", "abc")
        assertThat(entries.single().logoUrl).isEqualTo("https://e.com/l.png")
        assertThat(entries.single().name).isEqualTo("Name")
    }

    @Test
    fun `HLS media and master playlists are rejected`() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nseg0.ts\n#EXTINF:10,\nseg1.ts\n#EXT-X-ENDLIST\n"
        assertThat(M3uParser.parse(media, "https://e.com/index.m3u8")).isEmpty()
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nlow.m3u8\n"
        assertThat(M3uParser.parse(master)).isEmpty()
        val live = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:6,\na.ts\n"
        assertThat(M3uParser.parse(live)).isEmpty()
    }

    @Test
    fun `empty input yields no entries`() {
        assertThat(M3uParser.parse("")).isEmpty()
        assertThat(M3uParser.parse("#EXTM3U\n")).isEmpty()
    }
}
