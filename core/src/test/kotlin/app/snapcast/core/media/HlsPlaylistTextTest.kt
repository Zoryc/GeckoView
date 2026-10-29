package app.snapcast.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HlsPlaylistTextTest {
    private val playlist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:4,\nseg1.ts\n"

    @Test
    fun `a clean playlist is returned as is`() {
        assertThat(HlsPlaylistText.clean(playlist)).isSameInstanceAs(playlist)
        assertThat(HlsPlaylistText.clean(playlist.replace("\n", "\r\n"))).isEqualTo(playlist.replace("\n", "\r\n"))
    }

    @Test
    fun `byte order marks and blank lines before the header are dropped`() {
        assertThat(HlsPlaylistText.clean("﻿$playlist")).isEqualTo(playlist)
        assertThat(HlsPlaylistText.clean("﻿﻿\n \r\n$playlist")).isEqualTo(playlist)
    }

    @Test
    fun `a byte order mark at the start of a later line is dropped`() {
        // Two files joined together, each with its own mark.
        val joined = "#EXTM3U\n#EXTINF:4,\n﻿seg1.ts\n﻿#EXTINF:4,\nseg2.ts\n"
        assertThat(HlsPlaylistText.clean(joined)).isEqualTo("#EXTM3U\n#EXTINF:4,\nseg1.ts\n#EXTINF:4,\nseg2.ts\n")
    }

    @Test
    fun `old Mac line breaks become line feeds`() {
        assertThat(HlsPlaylistText.clean(playlist.replace('\n', '\r'))).isEqualTo(playlist)
    }

    @Test
    fun `an answer that is not a playlist is recognised for what it is`() {
        for (page in listOf("<!DOCTYPE html><html><head><title>Expired</title>", "\n<html lang=fr>", "<script></script><body>")) {
            assertThat(HlsPlaylistText.clean(page)).isNull()
            assertThat(HlsPlaylistText.whatInstead(page)).isEqualTo(HlsPlaylistText.Instead.WEB_PAGE)
        }
        // A French page in Latin-1, read as UTF-8: its accents become replacement characters.
        val latin1 = String("<html><head><title>Lien expir\u00e9</title>".toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        assertThat(HlsPlaylistText.whatInstead(latin1)).isEqualTo(HlsPlaylistText.Instead.WEB_PAGE)
        assertThat(HlsPlaylistText.whatInstead("{\"error\":\"token expired\"}")).isEqualTo(HlsPlaylistText.Instead.JSON)
        assertThat(HlsPlaylistText.whatInstead(" \n")).isEqualTo(HlsPlaylistText.Instead.NOTHING)
        assertThat(HlsPlaylistText.whatInstead("Access denied")).isEqualTo(HlsPlaylistText.Instead.OTHER)
        // A 16-byte key that happens to start like JSON is still binary.
        val key = byteArrayOf(0x7B, 0x01, 0x7F, 0x13, 0x22, -0x80, 0x45, 0x10, 0x2A, -0x01, 0x05, 0x66, 0x07, -0x30, 0x09, 0x0B)
        assertThat(HlsPlaylistText.whatInstead(String(key, Charsets.UTF_8))).isEqualTo(HlsPlaylistText.Instead.OTHER)
        assertThat(HlsPlaylistText.clean("")).isNull()
        assertThat(HlsPlaylistText.clean("#EXT-X-VERSION:3\n#EXTM3U\n")).isNull()
    }

    @Test
    fun `the header offset skips byte order marks and blank space, in bytes`() {
        fun offset(text: String) = HlsPlaylistText.headerOffset(text.toByteArray(Charsets.UTF_8))
        assertThat(offset(playlist)).isEqualTo(0)
        assertThat(offset("\uFEFF$playlist")).isEqualTo(3)
        assertThat(offset("\uFEFF\uFEFF\r\n $playlist")).isEqualTo(9)
        assertThat(offset("<html>")).isEqualTo(-1)
        assertThat(offset("#EXTM3")).isEqualTo(-1)
        assertThat(offset("")).isEqualTo(-1)
        // Only the bytes read so far count.
        assertThat(HlsPlaylistText.headerOffset("\uFEFF#EXTM3U".toByteArray(Charsets.UTF_8), count = 5)).isEqualTo(-1)
    }

    @Test
    fun `the proxy's sniffer sees a playlist behind several byte order marks`() {
        assertThat(MediaClassifier.sniffPlaylist("﻿﻿#EXTM3U\n")).isEqualTo(app.snapcast.core.model.StreamType.HLS)
    }
}
