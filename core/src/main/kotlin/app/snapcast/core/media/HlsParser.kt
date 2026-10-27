package app.snapcast.core.media

import app.snapcast.core.model.DrmInfo
import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant
import app.snapcast.core.util.UrlUtils

data class HlsKey(
    val method: String,
    val uri: String?,
    val keyFormat: String?,
    val keyFormatVersions: String?,
) {
    /**
     * True for DRM key systems (FairPlay/Widevine/PlayReady). Plain AES-128 or SAMPLE-AES with the
     * "identity" key format are not DRM: the key URI is fetchable and can be proxied.
     */
    val isDrm: Boolean
        get() {
            if (method.trim().uppercase() !in SAMPLE_ENCRYPTION_METHODS) return false
            if (uri?.trim()?.startsWith("skd://", ignoreCase = true) == true) return true
            val format = keyFormat?.trim()
            return !format.isNullOrEmpty() && !format.equals(IDENTITY_KEY_FORMAT, ignoreCase = true)
        }

    private companion object {
        /** Methods that can carry a DRM key system (ISO-23001-7 is the legacy name of SAMPLE-AES-CENC). */
        val SAMPLE_ENCRYPTION_METHODS = setOf("SAMPLE-AES", "SAMPLE-AES-CTR", "SAMPLE-AES-CENC", "ISO-23001-7")
        const val IDENTITY_KEY_FORMAT = "identity"
    }
}

data class HlsVariant(
    val url: String,
    val bandwidth: Long?,
    val averageBandwidth: Long?,
    val width: Int?,
    val height: Int?,
    val codecs: String?,
    val frameRate: Double?,
    val audioGroup: String?,
    val subtitlesGroup: String?,
) {
    fun toVideoVariant(): VideoVariant = VideoVariant(
        url = url,
        bandwidth = bandwidth,
        width = width,
        height = height,
        codecs = codecs,
        frameRate = frameRate,
    )
}

data class HlsRendition(
    /** AUDIO, VIDEO, SUBTITLES or CLOSED-CAPTIONS. */
    val type: String,
    val groupId: String?,
    val name: String?,
    val language: String?,
    /** Absolute URL, null for renditions muxed into the variant. */
    val url: String?,
    val isDefault: Boolean,
    val autoSelect: Boolean,
    val forced: Boolean,
)

sealed interface HlsPlaylist {
    data class Master(
        /** Sorted by descending height, then bandwidth. Absolute URLs. */
        val variants: List<HlsVariant>,
        val renditions: List<HlsRendition>,
        val sessionKeys: List<HlsKey>,
        /** Absolute URLs from EXT-X-I-FRAME-STREAM-INF. */
        val iFrameVariantUrls: List<String>,
    ) : HlsPlaylist {
        val subtitleRenditions: List<HlsRendition> get() = renditions.filter { it.type == "SUBTITLES" && it.url != null }
    }

    data class Media(
        val targetDurationSec: Double?,
        val totalDurationSec: Double,
        val segmentCount: Int,
        val hasEndList: Boolean,
        /** EXT-X-PLAYLIST-TYPE value (VOD / EVENT) or null. */
        val playlistType: String?,
        val keys: List<HlsKey>,
        /** True when EXT-X-MAP is present (fragmented MP4 / CMAF segments). */
        val hasInitSegment: Boolean,
        /** Absolute URL of the first media segment, if any. */
        val firstSegmentUrl: String?,
    ) : HlsPlaylist {
        val isLive: Boolean get() = !hasEndList && playlistType != "VOD"
    }
}

/** Minimal, tolerant HLS (RFC 8216) playlist parser. */
object HlsParser {
    private const val EXTM3U = "#EXTM3U"
    private const val STREAM_INF = "#EXT-X-STREAM-INF:"
    private const val I_FRAME_STREAM_INF = "#EXT-X-I-FRAME-STREAM-INF:"
    private const val MEDIA = "#EXT-X-MEDIA:"
    private const val SESSION_KEY = "#EXT-X-SESSION-KEY:"
    private const val KEY = "#EXT-X-KEY:"
    private const val MAP = "#EXT-X-MAP"
    private const val EXTINF = "#EXTINF:"
    private const val TARGET_DURATION = "#EXT-X-TARGETDURATION:"
    private const val PLAYLIST_TYPE = "#EXT-X-PLAYLIST-TYPE:"
    private const val END_LIST = "#EXT-X-ENDLIST"

    private const val DRM_REASON = "HLS SAMPLE-AES key"

    private val LINE_BREAK = Regex("\r\n|\r|\n")
    private val RESOLUTION = Regex("(\\d+)\\s*[xX]\\s*(\\d+)")

    /** Returns null when [text] is not an HLS playlist. Relative URIs are resolved against [playlistUrl]. */
    fun parse(text: String, playlistUrl: String): HlsPlaylist? {
        val lines = text.removePrefix("﻿").split(LINE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || !lines.first().startsWith(EXTM3U, ignoreCase = true)) return null
        val hasStreamInf = lines.any { it.startsWith(STREAM_INF, ignoreCase = true) }
        val hasSegments = lines.any { it.startsWith(EXTINF, ignoreCase = true) }
        val hasMasterOnlyTags = lines.any { line ->
            line.startsWith(I_FRAME_STREAM_INF, ignoreCase = true) ||
                line.startsWith(MEDIA, ignoreCase = true) ||
                line.startsWith(SESSION_KEY, ignoreCase = true)
        }
        return if (hasStreamInf || (!hasSegments && hasMasterOnlyTags)) {
            parseMaster(lines, playlistUrl)
        } else {
            parseMedia(lines, playlistUrl)
        }
    }

    private fun parseMaster(lines: List<String>, playlistUrl: String): HlsPlaylist.Master {
        val variants = mutableListOf<HlsVariant>()
        val renditions = mutableListOf<HlsRendition>()
        val sessionKeys = mutableListOf<HlsKey>()
        val iFrames = mutableListOf<String>()
        var pendingStreamInf: Map<String, String>? = null

        for (line in lines) {
            when {
                line.startsWith(STREAM_INF, ignoreCase = true) ->
                    pendingStreamInf = parseAttributes(line.substring(STREAM_INF.length))
                line.startsWith(I_FRAME_STREAM_INF, ignoreCase = true) ->
                    parseAttributes(line.substring(I_FRAME_STREAM_INF.length))["URI"]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { iFrames += UrlUtils.resolve(playlistUrl, it) }
                line.startsWith(MEDIA, ignoreCase = true) ->
                    renditions += rendition(parseAttributes(line.substring(MEDIA.length)), playlistUrl)
                line.startsWith(SESSION_KEY, ignoreCase = true) ->
                    key(parseAttributes(line.substring(SESSION_KEY.length)), playlistUrl)?.let { sessionKeys += it }
                line.startsWith("#") -> Unit
                else -> pendingStreamInf?.let { attrs ->
                    variants += variant(attrs, UrlUtils.resolve(playlistUrl, line))
                    pendingStreamInf = null
                }
            }
        }
        val sorted = variants.sortedWith(
            compareByDescending<HlsVariant> { it.height ?: -1 }.thenByDescending { it.bandwidth ?: -1L },
        )
        return HlsPlaylist.Master(sorted, renditions, sessionKeys.distinct(), iFrames.distinct())
    }

    private fun variant(attrs: Map<String, String>, url: String): HlsVariant {
        val resolution = attrs["RESOLUTION"]?.let { RESOLUTION.find(it) }
        return HlsVariant(
            url = url,
            bandwidth = attrs["BANDWIDTH"]?.trim()?.toLongOrNull(),
            averageBandwidth = attrs["AVERAGE-BANDWIDTH"]?.trim()?.toLongOrNull(),
            width = resolution?.groupValues?.get(1)?.toIntOrNull(),
            height = resolution?.groupValues?.get(2)?.toIntOrNull(),
            codecs = attrs["CODECS"]?.takeIf { it.isNotBlank() },
            frameRate = attrs["FRAME-RATE"]?.trim()?.toDoubleOrNull()?.takeIf { it > 0 },
            audioGroup = attrs["AUDIO"]?.takeIf { it.isNotBlank() },
            subtitlesGroup = attrs["SUBTITLES"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun rendition(attrs: Map<String, String>, playlistUrl: String): HlsRendition = HlsRendition(
        type = attrs["TYPE"]?.trim()?.uppercase().orEmpty(),
        groupId = attrs["GROUP-ID"],
        name = attrs["NAME"],
        language = attrs["LANGUAGE"]?.takeIf { it.isNotBlank() },
        url = attrs["URI"]?.takeIf { it.isNotBlank() }?.let { UrlUtils.resolve(playlistUrl, it) },
        isDefault = attrs["DEFAULT"].isYes(),
        autoSelect = attrs["AUTOSELECT"].isYes(),
        forced = attrs["FORCED"].isYes(),
    )

    private fun String?.isYes(): Boolean = this?.trim().equals("YES", ignoreCase = true)

    /** Builds a key from EXT-X-KEY / EXT-X-SESSION-KEY attributes; METHOD=NONE (or missing) yields null. */
    private fun key(attrs: Map<String, String>, playlistUrl: String): HlsKey? {
        val method = attrs["METHOD"]?.trim()?.uppercase()
        if (method.isNullOrEmpty() || method == "NONE") return null
        val uri = attrs["URI"]?.trim()?.takeIf { it.isNotEmpty() }?.let { UrlUtils.resolve(playlistUrl, it) }
        return HlsKey(
            method = method,
            uri = uri,
            keyFormat = attrs["KEYFORMAT"]?.trim()?.takeIf { it.isNotEmpty() },
            keyFormatVersions = attrs["KEYFORMATVERSIONS"]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseMedia(lines: List<String>, playlistUrl: String): HlsPlaylist.Media {
        var targetDuration: Double? = null
        var total = 0.0
        var segments = 0
        var endList = false
        var playlistType: String? = null
        val keys = LinkedHashSet<HlsKey>()
        var hasMap = false
        var firstSegment: String? = null

        for (line in lines) {
            when {
                line.startsWith(EXTINF, ignoreCase = true) -> {
                    val value = line.substring(EXTINF.length).substringBefore(',').trim()
                    value.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { total += it }
                }
                line.startsWith(TARGET_DURATION, ignoreCase = true) ->
                    targetDuration = line.substring(TARGET_DURATION.length).trim().toDoubleOrNull()
                line.startsWith(PLAYLIST_TYPE, ignoreCase = true) ->
                    playlistType = line.substring(PLAYLIST_TYPE.length).trim().uppercase().takeIf { it.isNotEmpty() }
                line.equals(END_LIST, ignoreCase = true) -> endList = true
                line.startsWith(KEY, ignoreCase = true) ->
                    key(parseAttributes(line.substring(KEY.length)), playlistUrl)?.let { keys += it }
                line.startsWith(MAP, ignoreCase = true) &&
                    (line.length == MAP.length || line[MAP.length] == ':') -> hasMap = true
                line.startsWith("#") -> Unit
                else -> {
                    segments++
                    if (firstSegment == null) firstSegment = UrlUtils.resolve(playlistUrl, line)
                }
            }
        }
        return HlsPlaylist.Media(
            targetDurationSec = targetDuration,
            totalDurationSec = total,
            segmentCount = segments,
            hasEndList = endList,
            playlistType = playlistType,
            keys = keys.toList(),
            hasInitSegment = hasMap,
            firstSegmentUrl = firstSegment,
        )
    }

    /**
     * Parses an attribute list like `BANDWIDTH=1280000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=1280x720`.
     * Names are upper-cased; quoted values lose their quotes and may contain commas; other values are trimmed.
     */
    fun parseAttributes(attributeList: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val s = attributeList
        var i = 0
        while (i < s.length) {
            while (i < s.length && (s[i] == ',' || s[i].isWhitespace())) i++
            if (i >= s.length) break
            val nameStart = i
            while (i < s.length && s[i] != '=' && s[i] != ',') i++
            val name = s.substring(nameStart, i).trim().uppercase()
            if (i >= s.length || s[i] == ',') continue // attribute without a value: ignore it
            i++ // skip '='
            while (i < s.length && s[i] != ',' && s[i] != '"' && s[i].isWhitespace()) i++
            val value: String
            if (i < s.length && s[i] == '"') {
                val close = s.indexOf('"', i + 1)
                if (close < 0) {
                    value = s.substring(i + 1)
                    i = s.length
                } else {
                    value = s.substring(i + 1, close)
                    i = close + 1
                    while (i < s.length && s[i] != ',') i++
                }
            } else {
                val end = s.indexOf(',', i).let { if (it < 0) s.length else it }
                value = s.substring(i, end).trim()
                i = end
            }
            if (name.isNotEmpty()) result[name] = value
        }
        return result
    }

    /** DRM signal for a parsed playlist (session keys of a master, keys of a media playlist), or null. */
    fun detectDrm(playlist: HlsPlaylist): DrmInfo? {
        val keys = when (playlist) {
            is HlsPlaylist.Master -> playlist.sessionKeys
            is HlsPlaylist.Media -> playlist.keys
        }
        val drmKey = keys.firstOrNull { it.isDrm } ?: return null
        return DrmInfo(system = drmSystemName(drmKey), reason = DRM_REASON)
    }

    private fun drmSystemName(key: HlsKey): String? {
        val format = key.keyFormat?.trim()?.lowercase()
        return when {
            format == "com.apple.streamingkeydelivery" -> "FairPlay"
            format == "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" || format == "com.widevine" -> "Widevine"
            format == "com.microsoft.playready" || format == "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95" -> "PlayReady"
            format == "urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e" ||
                format == "urn:uuid:1077efec-c0b2-4d02-ace3-3c1e52e2fb4b" ||
                format == "org.w3.clearkey" -> "ClearKey"
            format.isNullOrEmpty() && key.uri?.startsWith("skd://", ignoreCase = true) == true -> "FairPlay"
            else -> key.keyFormat?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Subtitle tracks advertised by a master playlist (SUBTITLES renditions), as [SubtitleTrack]s with format
     * VTT flagged [SubtitleTrack.isHlsRendition]: each URL is a media playlist of WebVTT segments that players
     * get from the master itself. Renditions whose URI is not http(s) (a page could name file:// or content://
     * URIs) are dropped.
     */
    fun subtitleTracks(master: HlsPlaylist.Master): List<SubtitleTrack> =
        master.subtitleRenditions.mapNotNull { rendition ->
            val url = rendition.url?.takeIf { UrlUtils.isHttp(it) } ?: return@mapNotNull null
            SubtitleTrack(
                id = UrlUtils.stableId(url),
                url = url,
                label = rendition.name?.takeIf { it.isNotBlank() },
                language = rendition.language,
                format = SubtitleFormat.VTT,
                isHlsRendition = true,
            )
        }.distinctBy { it.id }

    /**
     * Variants mapped to [VideoVariant], best first, de-duplicated by resolution+bandwidth. A variant whose
     * audio plays from a separate URI (the rendition a player picks by default in its AUDIO group: DEFAULT,
     * else AUTOSELECT, else the first) is marked [VideoVariant.separateAudio]. Alternate languages with their
     * own URI do not count: the variant's playlist still carries the default audio.
     */
    fun videoVariants(master: HlsPlaylist.Master): List<VideoVariant> =
        master.variants
            .filterNot(::isAudioOnly)
            .sortedWith(compareByDescending<HlsVariant> { it.height ?: -1 }.thenByDescending { it.bandwidth ?: -1L })
            .distinctBy { Triple(it.width, it.height, it.bandwidth) }
            .map { variant -> variant.toVideoVariant().copy(separateAudio = hasSeparateAudio(master, variant)) }

    /**
     * A variant with no picture: no resolution and only audio codecs (Apple's guidelines recommend an audio-only
     * variant in every master). It is not a video quality to label or offer.
     */
    private fun isAudioOnly(variant: HlsVariant): Boolean {
        if (variant.height != null || variant.width != null) return false
        val codecs = variant.codecs?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: return false
        return codecs.isNotEmpty() && codecs.all { codec -> AUDIO_CODECS.any { codec.startsWith(it) } }
    }

    private val AUDIO_CODECS = listOf("mp4a", "ac-3", "ec-3", "ac-4", "opus", "flac", "mp3", "alac", "dtsc", "dtse", "dtsh", "dtsl")

    private fun hasSeparateAudio(master: HlsPlaylist.Master, variant: HlsVariant): Boolean {
        val group = variant.audioGroup ?: return false
        val renditions = master.renditions.filter { it.type == "AUDIO" && it.groupId == group }
        val played = renditions.firstOrNull { it.isDefault } ?: renditions.firstOrNull { it.autoSelect } ?: renditions.firstOrNull()
        return played?.url != null
    }
}
