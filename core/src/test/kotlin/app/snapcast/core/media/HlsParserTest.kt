package app.snapcast.core.media

import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.util.UrlUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertIs

class HlsParserTest {
    private val masterUrl = "https://cdn.example.com/vod/movie/master.m3u8?token=abc"

    private val master = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI="skd://key-1",KEYFORMAT="com.apple.streamingkeydelivery",KEYFORMATVERSIONS="1"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio/en.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Commentary, director",LANGUAGE="en",URI="audio/dir.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Deutsch",LANGUAGE="de",FORCED=NO,AUTOSELECT=YES,URI="subs/de.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="/subs/en.m3u8"
        #EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID="cc",NAME="CC1",INSTREAM-ID="CC1"
        #EXT-X-STREAM-INF:BANDWIDTH=1280000,AVERAGE-BANDWIDTH=1000000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=1280x720,FRAME-RATE=29.970,AUDIO="aud",SUBTITLES="subs"
        720p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=640000,RESOLUTION=640x360,CODECS="avc1.42e01e,mp4a.40.2"
        # a comment between the tag and its URI

        360p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,FRAME-RATE=60,CODECS="avc1.640028,mp4a.40.2"
        https://other.cdn.net/1080p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.2"
        audio-only.m3u8
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=86000,URI="iframes/720p.m3u8"
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=50000,URI="iframes/360p.m3u8"
    """.trimIndent()

    @Test
    fun `parses master variants sorted by height then bandwidth`() {
        val playlist = assertIs<HlsPlaylist.Master>(HlsParser.parse(master, masterUrl))
        assertThat(playlist.variants.map { it.url }).containsExactly(
            "https://other.cdn.net/1080p/index.m3u8",
            "https://cdn.example.com/vod/movie/720p/index.m3u8",
            "https://cdn.example.com/vod/movie/360p/index.m3u8",
            "https://cdn.example.com/vod/movie/audio-only.m3u8",
        ).inOrder()

        val hd = playlist.variants[1]
        assertThat(hd.bandwidth).isEqualTo(1_280_000L)
        assertThat(hd.averageBandwidth).isEqualTo(1_000_000L)
        assertThat(hd.width).isEqualTo(1280)
        assertThat(hd.height).isEqualTo(720)
        assertThat(hd.codecs).isEqualTo("avc1.4d401f,mp4a.40.2")
        assertThat(hd.frameRate).isWithin(0.0001).of(29.97)
        assertThat(hd.audioGroup).isEqualTo("aud")
        assertThat(hd.subtitlesGroup).isEqualTo("subs")

        val audioOnly = playlist.variants.last()
        assertThat(audioOnly.width).isNull()
        assertThat(audioOnly.height).isNull()
    }

    @Test
    fun `parses renditions i-frames and session keys`() {
        val playlist = assertIs<HlsPlaylist.Master>(HlsParser.parse(master, masterUrl))
        assertThat(playlist.renditions).hasSize(5)
        val english = playlist.renditions.first()
        assertThat(english).isEqualTo(
            HlsRendition(
                type = "AUDIO",
                groupId = "aud",
                name = "English",
                language = "en",
                url = "https://cdn.example.com/vod/movie/audio/en.m3u8",
                isDefault = true,
                autoSelect = true,
                forced = false,
            ),
        )
        assertThat(playlist.renditions[1].name).isEqualTo("Commentary, director")
        assertThat(playlist.renditions.last().url).isNull()
        assertThat(playlist.subtitleRenditions.map { it.url }).containsExactly(
            "https://cdn.example.com/vod/movie/subs/de.m3u8",
            "https://cdn.example.com/subs/en.m3u8",
        ).inOrder()
        assertThat(playlist.iFrameVariantUrls).containsExactly(
            "https://cdn.example.com/vod/movie/iframes/720p.m3u8",
            "https://cdn.example.com/vod/movie/iframes/360p.m3u8",
        ).inOrder()
        assertThat(playlist.sessionKeys).containsExactly(
            HlsKey("SAMPLE-AES", "skd://key-1", "com.apple.streamingkeydelivery", "1"),
        )
    }

    @Test
    fun `master session key signals FairPlay DRM`() {
        val playlist = HlsParser.parse(master, masterUrl)!!
        val drm = HlsParser.detectDrm(playlist)
        assertThat(drm).isNotNull()
        assertThat(drm!!.system).isEqualTo("FairPlay")
        assertThat(drm.reason).isEqualTo("HLS SAMPLE-AES key")
    }

    @Test
    fun `subtitleTracks maps subtitle renditions to VTT tracks`() {
        val playlist = assertIs<HlsPlaylist.Master>(HlsParser.parse(master, masterUrl))
        val tracks = HlsParser.subtitleTracks(playlist)
        assertThat(tracks).hasSize(2)
        assertThat(tracks[0].url).isEqualTo("https://cdn.example.com/vod/movie/subs/de.m3u8")
        assertThat(tracks[0].id).isEqualTo(UrlUtils.stableId(tracks[0].url))
        assertThat(tracks[0].label).isEqualTo("Deutsch")
        assertThat(tracks[0].language).isEqualTo("de")
        assertThat(tracks.map { it.format }).containsExactly(SubtitleFormat.VTT, SubtitleFormat.VTT)
        assertThat(tracks.map { it.isHlsRendition }).containsExactly(true, true)
        assertThat(tracks.map { it.isLocal }).containsExactly(false, false)
    }

    @Test
    fun `subtitle renditions with non-network URIs are dropped`() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",NAME="a",URI="file:///data/data/app.snapcast/shared_prefs/x.xml"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",NAME="b",URI="content://app.snapcast.browser.fileprovider/d/x"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",NAME="c",URI="subs/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,SUBTITLES="s"
            v.m3u8
        """.trimIndent()
        val playlist = assertIs<HlsPlaylist.Master>(HlsParser.parse(text, "https://e.com/hls/master.m3u8"))
        assertThat(HlsParser.subtitleTracks(playlist).map { it.url }).containsExactly("https://e.com/hls/subs/en.m3u8")
    }

    @Test
    fun `variants whose audio group has its own playlists are marked separate audio`() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud1",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="a1/prog_index.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="muxed",NAME="English",LANGUAGE="en",DEFAULT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud1"
            v1080/prog_index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO="muxed"
            v720/prog_index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            v360/prog_index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=480x270,AUDIO="unknown"
            v270/prog_index.m3u8
        """.trimIndent()
        val playlist = assertIs<HlsPlaylist.Master>(HlsParser.parse(text, "https://e.com/m.m3u8"))
        val variants = HlsParser.videoVariants(playlist)
        assertThat(variants.map { it.height to it.separateAudio })
            .containsExactly(1080 to true, 720 to false, 360 to false, 270 to false)
            .inOrder()
    }

    @Test
    fun `an alternate language with its own playlist does not make muxed audio separate`() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Français",LANGUAGE="fr",AUTOSELECT=YES,URI="fr/audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud"
            v1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aud"
            v360.m3u8
        """.trimIndent()
        val playlist = assertIs<HlsPlaylist.Master>(HlsParser.parse(text, "https://e.com/m.m3u8"))
        assertThat(HlsParser.videoVariants(playlist).map { it.separateAudio }).containsExactly(false, false)
    }

    @Test
    fun `videoVariants are best first and de-duplicated by resolution and bandwidth`() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO="a1"
            v720-a1.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO="a2"
            v720-a2.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
            v720-hq.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,FRAME-RATE=50
            v1080.m3u8
        """.trimIndent()
        val playlist = assertIs<HlsPlaylist.Master>(HlsParser.parse(text, "https://e.com/m.m3u8"))
        val variants = HlsParser.videoVariants(playlist)
        assertThat(variants.map { it.url }).containsExactly(
            "https://e.com/v1080.m3u8",
            "https://e.com/v720-hq.m3u8",
            "https://e.com/v720-a1.m3u8",
        ).inOrder()
        assertThat(variants.first().qualityLabel).isEqualTo("1080p · 50fps")
        assertThat(variants.first().frameRate).isEqualTo(50.0)
    }

    @Test
    fun `tolerates BOM CRLF lowercase resolution separator and whitespace`() {
        val text = "﻿#EXTM3U\r\n\r\n#EXT-X-STREAM-INF:BANDWIDTH=800000, RESOLUTION=854X480 ,CODECS=\"avc1.4d401e,mp4a.40.2\"\r\n" +
            "\r\n  480p.m3u8  \r\n"
        val playlist = assertIs<HlsPlaylist.Master>(HlsParser.parse(text, "https://e.com/a/master.m3u8"))
        val variant = playlist.variants.single()
        assertThat(variant.url).isEqualTo("https://e.com/a/480p.m3u8")
        assertThat(variant.width).isEqualTo(854)
        assertThat(variant.height).isEqualTo(480)
        assertThat(variant.codecs).isEqualTo("avc1.4d401e,mp4a.40.2")
    }

    @Test
    fun `returns null for non playlists`() {
        assertThat(HlsParser.parse("<html><body>Not found</body></html>", masterUrl)).isNull()
        assertThat(HlsParser.parse("", masterUrl)).isNull()
        assertThat(HlsParser.parse("   \n  ", masterUrl)).isNull()
        assertThat(HlsParser.parse("#EXTINF:10,\nseg.ts", masterUrl)).isNull()
    }

    @Test
    fun `parses VOD media playlist with relative segments`() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:9.009,
            ../segments/seg0.ts
            #EXTINF:9.009,title with, commas
            seg1.ts
            #EXTINF:3.5
            seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val playlist = assertIs<HlsPlaylist.Media>(HlsParser.parse(text, "https://e.com/hls/720p/index.m3u8"))
        assertThat(playlist.targetDurationSec).isEqualTo(10.0)
        assertThat(playlist.totalDurationSec).isWithin(1e-9).of(21.518)
        assertThat(playlist.segmentCount).isEqualTo(3)
        assertThat(playlist.hasEndList).isTrue()
        assertThat(playlist.playlistType).isEqualTo("VOD")
        assertThat(playlist.isLive).isFalse()
        assertThat(playlist.keys).isEmpty()
        assertThat(playlist.hasInitSegment).isFalse()
        assertThat(playlist.firstSegmentUrl).isEqualTo("https://e.com/hls/segments/seg0.ts")
        assertThat(HlsParser.detectDrm(playlist)).isNull()
    }

    @Test
    fun `live and event playlists`() {
        val live = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:1234
            #EXTINF:6.0,
            https://live.e.com/seg1234.ts
            #EXTINF:6.0,
            https://live.e.com/seg1235.ts
        """.trimIndent()
        val livePlaylist = assertIs<HlsPlaylist.Media>(HlsParser.parse(live, "https://live.e.com/index.m3u8"))
        assertThat(livePlaylist.isLive).isTrue()
        assertThat(livePlaylist.hasEndList).isFalse()
        assertThat(livePlaylist.playlistType).isNull()

        val event = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXTINF:4,\na.ts\n"
        val eventPlaylist = assertIs<HlsPlaylist.Media>(HlsParser.parse(event, "https://e.com/e.m3u8"))
        assertThat(eventPlaylist.playlistType).isEqualTo("EVENT")
        assertThat(eventPlaylist.isLive).isTrue()

        val vodWithoutEndList = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4,\na.ts\n"
        assertThat(assertIs<HlsPlaylist.Media>(HlsParser.parse(vodWithoutEndList, "https://e.com/v.m3u8")).isLive).isFalse()
    }

    @Test
    fun `empty media playlist`() {
        val playlist = assertIs<HlsPlaylist.Media>(HlsParser.parse("#EXTM3U\n#EXT-X-TARGETDURATION:4\n", "https://e.com/x.m3u8"))
        assertThat(playlist.segmentCount).isEqualTo(0)
        assertThat(playlist.firstSegmentUrl).isNull()
        assertThat(playlist.totalDurationSec).isEqualTo(0.0)
    }

    @Test
    fun `fMP4 media playlist with EXT-X-MAP and AES-128 key is not DRM`() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.e.com/k?id=1",IV=0x0123456789abcdef0123456789abcdef
            #EXTINF:4.0,
            seg0.m4s
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.e.com/k?id=1",IV=0x0123456789abcdef0123456789abcdef
            #EXTINF:4.0,
            seg1.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val playlist = assertIs<HlsPlaylist.Media>(HlsParser.parse(text, "https://e.com/v/index.m3u8"))
        assertThat(playlist.hasInitSegment).isTrue()
        assertThat(playlist.keys).containsExactly(HlsKey("AES-128", "https://keys.e.com/k?id=1", null, null))
        assertThat(playlist.keys.single().isDrm).isFalse()
        assertThat(HlsParser.detectDrm(playlist)).isNull()
        assertThat(playlist.firstSegmentUrl).isEqualTo("https://e.com/v/seg0.m4s")
    }

    @Test
    fun `METHOD NONE keys are ignored and relative key URIs resolved`() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            a.ts
            #EXT-X-KEY:METHOD=AES-128,URI="../keys/key.bin"
            #EXTINF:4,
            b.ts
        """.trimIndent()
        val playlist = assertIs<HlsPlaylist.Media>(HlsParser.parse(text, "https://e.com/hls/v/index.m3u8"))
        assertThat(playlist.keys.map { it.uri }).containsExactly("https://e.com/hls/keys/key.bin")
    }

    @Test
    fun `Widevine SAMPLE-AES-CTR key is DRM`() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI="data:text/plain;base64,AAAAW3Bzc2g=",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",KEYFORMATVERSIONS="1"
            #EXTINF:4,
            seg0.m4s
        """.trimIndent()
        val playlist = HlsParser.parse(text, "https://e.com/v.m3u8")!!
        val drm = HlsParser.detectDrm(playlist)!!
        assertThat(drm.system).isEqualTo("Widevine")
        assertThat(drm.reason).isEqualTo("HLS SAMPLE-AES key")
    }

    @Test
    fun `PlayReady key formats are DRM`() {
        listOf("com.microsoft.playready", "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95").forEach { format ->
            val text = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"https://lic.e.com/\",KEYFORMAT=\"$format\"\n#EXTINF:4,\na.ts\n"
            assertThat(HlsParser.detectDrm(HlsParser.parse(text, "https://e.com/v.m3u8")!!)?.system).isEqualTo("PlayReady")
        }
    }

    @Test
    fun `identity SAMPLE-AES is not DRM`() {
        val identity = HlsKey("SAMPLE-AES", "https://keys.e.com/k", "identity", null)
        val implicitIdentity = HlsKey("SAMPLE-AES", "https://keys.e.com/k", null, null)
        assertThat(identity.isDrm).isFalse()
        assertThat(implicitIdentity.isDrm).isFalse()
        val text = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k.bin\",KEYFORMAT=\"identity\"\n#EXTINF:4,\na.ts\n"
        assertThat(HlsParser.detectDrm(HlsParser.parse(text, "https://e.com/v.m3u8")!!)).isNull()
    }

    @Test
    fun `HlsKey isDrm rules`() {
        assertThat(HlsKey("AES-128", "https://k", null, null).isDrm).isFalse()
        assertThat(HlsKey("AES-128", "https://k", "com.apple.streamingkeydelivery", null).isDrm).isFalse()
        assertThat(HlsKey("SAMPLE-AES", "skd://abc", null, null).isDrm).isTrue()
        assertThat(HlsKey("sample-aes", "https://k", "com.apple.streamingkeydelivery", "1").isDrm).isTrue()
        assertThat(HlsKey("SAMPLE-AES-CENC", null, "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", null).isDrm).isTrue()
        assertThat(HlsKey("SAMPLE-AES-CTR", "https://k", "IDENTITY", null).isDrm).isFalse()
    }

    @Test
    fun `skd key without key format is reported as FairPlay`() {
        val text = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://asset-1\"\n#EXTINF:4,\na.ts\n"
        assertThat(HlsParser.detectDrm(HlsParser.parse(text, "https://e.com/v.m3u8")!!)?.system).isEqualTo("FairPlay")
    }

    @Test
    fun `parseAttributes handles quotes commas and lowercase hex`() {
        val attrs = HlsParser.parseAttributes(
            "BANDWIDTH=1280000,CODECS=\"avc1.4d401f,mp4a.40.2\",RESOLUTION=1280x720,IV=0xabcdef0123,NAME=\"a=b, c\",flag",
        )
        assertThat(attrs).containsExactly(
            "BANDWIDTH", "1280000",
            "CODECS", "avc1.4d401f,mp4a.40.2",
            "RESOLUTION", "1280x720",
            "IV", "0xabcdef0123",
            "NAME", "a=b, c",
        ).inOrder()
    }

    @Test
    fun `parseAttributes tolerates spaces lowercase names and unterminated quotes`() {
        val attrs = HlsParser.parseAttributes(" uri = \"a b.m3u8\" , Type=AUDIO, NAME=\"unterminated")
        assertThat(attrs["URI"]).isEqualTo("a b.m3u8")
        assertThat(attrs["TYPE"]).isEqualTo("AUDIO")
        assertThat(attrs["NAME"]).isEqualTo("unterminated")
        assertThat(HlsParser.parseAttributes("")).isEmpty()
    }

    @Test
    fun `master with only renditions and session keys is still a master`() {
        val text = "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",NAME=\"en\",URI=\"en.m3u8\"\n"
        assertIs<HlsPlaylist.Master>(HlsParser.parse(text, "https://e.com/m.m3u8"))
    }
}
