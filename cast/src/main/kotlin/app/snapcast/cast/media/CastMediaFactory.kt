package app.snapcast.cast.media

import app.snapcast.cast.CastMedia
import app.snapcast.cast.CastSubtitle
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant
import app.snapcast.core.util.CookieHeaders
import app.snapcast.core.util.UrlUtils
import app.snapcast.proxy.ProxyStreamRequest
import app.snapcast.proxy.SubtitleSource
import kotlin.math.roundToLong

/**
 * Builds receiver-ready [CastMedia] from a [DetectedVideo].
 *
 * Text tracks get stable ids: the n-th entry of [DetectedVideo.subtitles] (de-duplicated by URL) is track
 * `n`, and a chosen subtitle that is not part of the list is appended as the last id. Tracks that cannot be
 * served are skipped without renumbering the others, so an id always designates the same subtitle.
 */
internal object CastMediaFactory {
    /** Captured headers that belong to the captured request's host only. */
    private val HOST_CREDENTIALS = listOf("Cookie", "Authorization")

    /**
     * Media served through the proxy: the stream (or [variant]) is registered with the captured headers and
     * cookies, and every usable subtitle is registered as a WebVTT resource.
     *
     * @param localSubtitles sources for subtitles stored on the phone, keyed by [SubtitleTrack.id].
     */
    fun proxied(
        video: DetectedVideo,
        variant: VideoVariant?,
        subtitle: SubtitleTrack?,
        proxy: MediaProxy,
        localSubtitles: Map<String, SubtitleSource> = emptyMap(),
    ): CastMedia {
        val stream = proxy.registerStream(streamRequest(video, streamUrl(video, variant)))
        val tracks = ArrayList<CastSubtitle>()
        var activeTrackId: Long? = null
        subtitleCandidates(video, subtitle).forEachIndexed { index, track ->
            val source = localSubtitles[track.id] ?: remoteSubtitleSource(video, track) ?: return@forEachIndexed
            val trackId = index + 1L
            val registered = proxy.registerSubtitle(source)
            tracks += CastSubtitle(
                trackId = trackId,
                url = proxy.url(registered.path),
                language = track.language,
                name = CastMediaRules.trackName(track, trackId),
            )
            if (subtitle != null && CastMediaRules.isSameSubtitle(track, subtitle)) activeTrackId = trackId
        }
        return baseMedia(video, proxy.url(stream.path), tracks, activeTrackId)
    }

    /** Media the receiver fetches from the origin itself; only plain http(s) WebVTT subtitles are attached. */
    fun direct(video: DetectedVideo, variant: VideoVariant?, subtitle: SubtitleTrack?): CastMedia {
        val tracks = ArrayList<CastSubtitle>()
        var activeTrackId: Long? = null
        subtitleCandidates(video, subtitle).forEachIndexed { index, track ->
            if (!CastMediaRules.isDirectSubtitle(track)) return@forEachIndexed
            val trackId = index + 1L
            tracks += CastSubtitle(trackId, track.url, track.language, CastMediaRules.trackName(track, trackId))
            if (subtitle != null && CastMediaRules.isSameSubtitle(track, subtitle)) activeTrackId = trackId
        }
        return baseMedia(video, streamUrl(video, variant), tracks, activeTrackId)
    }

    /**
     * The URL the receiver plays: the chosen [variant]'s playlist, or the master when none is chosen or the
     * variant's audio is a separate rendition (its playlist alone would play without sound).
     */
    fun streamUrl(video: DetectedVideo, variant: VideoVariant?): String =
        variant?.takeUnless { it.separateAudio }?.url ?: video.url

    /**
     * The stream registration. The captured Cookie and Authorization belong to [DetectedVideo.url]'s host, also
     * when a variant on another host is registered instead: each host then gets exactly what it gets when the
     * master is cast (the variant host its own cookies, the master host, for keys say, its credentials).
     */
    fun streamRequest(video: DetectedVideo, streamUrl: String): ProxyStreamRequest = ProxyStreamRequest(
        url = streamUrl,
        headers = video.headers.toMap(),
        cookiesByHost = video.cookiesByHost,
        typeHint = video.type.takeUnless { it == StreamType.OTHER },
        credentialsUrl = video.url.takeIf { it != streamUrl },
    )

    /** The video's subtitles without URL duplicates, plus [chosen] when it is not one of them. */
    fun subtitleCandidates(video: DetectedVideo, chosen: SubtitleTrack?): List<SubtitleTrack> {
        val candidates = video.subtitles.distinctBy { it.url }.toMutableList()
        if (chosen != null && candidates.none { CastMediaRules.isSameSubtitle(it, chosen) }) candidates += chosen
        return candidates
    }

    /**
     * Headers for fetching a subtitle: the stream's headers, with the cookie of the subtitle's own host. The
     * stream's own credentials (captured Cookie and Authorization) only go back to the stream's host.
     */
    fun subtitleHeaders(video: DetectedVideo, subtitleUrl: String): Map<String, String> {
        val all = video.headers.toMap()
        val headers = all.filterKeys { name -> HOST_CREDENTIALS.none { it.equals(name, ignoreCase = true) } }.toMutableMap()
        val host = UrlUtils.hostOf(subtitleUrl)
        val onStreamHost = host != null && host == UrlUtils.hostOf(video.url)
        val cookie = when {
            host == null -> null
            onStreamHost -> CookieHeaders.merge(video.headers.cookie, video.cookiesByHost[host])
            else -> video.cookiesByHost[host]
        }
        cookie?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        if (onStreamHost) all.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.let { headers[it.key] = it.value }
        return headers
    }

    private fun remoteSubtitleSource(video: DetectedVideo, track: SubtitleTrack): SubtitleSource? {
        if (!CastMediaRules.isProxyableSubtitle(track)) return null
        // An undeclared format stays UNKNOWN: the proxy then sniffs the content, which beats guessing from the URL.
        return SubtitleSource.Remote(
            url = track.url,
            headers = subtitleHeaders(video, track.url),
            format = track.format,
        )
    }

    private fun baseMedia(
        video: DetectedVideo,
        contentUrl: String,
        tracks: List<CastSubtitle>,
        activeTrackId: Long?,
    ): CastMedia = CastMedia(
        contentUrl = contentUrl,
        contentType = CastMediaRules.contentType(video.type, video.mimeType),
        isLive = video.isLive,
        title = video.displayTitle,
        subtitle = video.sourceDomain.ifBlank { null },
        posterUrl = video.posterUrl?.takeIf { UrlUtils.isHttp(it) },
        durationMs = durationMs(video),
        textTracks = tracks,
        activeTextTrackId = activeTrackId,
        hlsSegmentFormat = CastMediaRules.hlsSegmentFormat(video.type, video.hlsFragmentedMp4),
        originalUrl = video.url,
    )

    private fun durationMs(video: DetectedVideo): Long? {
        if (video.isLive) return null
        return video.durationSec?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).roundToLong() }
    }
}
