package app.snapcast.cast.chromecast

import android.net.Uri
import app.snapcast.cast.CastMedia
import app.snapcast.cast.CastSubtitle
import app.snapcast.cast.HlsSegmentFormat
import app.snapcast.cast.media.CastMediaRules
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.common.images.WebImage
import org.json.JSONObject
import com.google.android.gms.cast.HlsSegmentFormat as GmsHlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat as GmsHlsVideoSegmentFormat

/** Converts between [CastMedia] and the Cast SDK's [MediaInfo] / [MediaQueueItem]. */
internal object MediaInfoMapper {
    private const val KEY_ORIGINAL_URL = "originalUrl"
    private const val KEY_HLS_SEGMENT_FORMAT = "hlsSegmentFormat"

    /** Seconds before the end of an item at which the receiver preloads the next queue item. */
    private const val PRELOAD_TIME_S = 20.0

    fun toMediaInfo(media: CastMedia): MediaInfo {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, media.title)
            media.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            media.posterUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val tracks = media.textTracks.map(::toMediaTrack)
        val customData = JSONObject().apply {
            put(KEY_ORIGINAL_URL, media.originalUrl)
            media.hlsSegmentFormat?.let { put(KEY_HLS_SEGMENT_FORMAT, it.name) }
        }
        val builder = MediaInfo.Builder(media.contentUrl)
            .setContentUrl(media.contentUrl)
            .setContentType(media.contentType)
            .setStreamType(if (media.isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata)
            .setCustomData(customData)
        media.durationMs?.takeIf { it > 0 && !media.isLive }?.let { builder.setStreamDuration(it) }
        if (tracks.isNotEmpty()) builder.setMediaTracks(tracks)
        media.hlsSegmentFormat?.let { format ->
            builder.setHlsSegmentFormat(sdkSegmentFormat(format))
            sdkVideoSegmentFormat(format)?.let { builder.setHlsVideoSegmentFormat(it) }
        }
        return builder.build()
    }

    /** Queue item for [media]; [startPositionMs] is set on the item that starts a queue load. */
    fun toQueueItem(media: CastMedia, startPositionMs: Long? = null): MediaQueueItem {
        val builder = MediaQueueItem.Builder(toMediaInfo(media))
            .setAutoplay(true)
            .setPreloadTime(PRELOAD_TIME_S)
        startPositionMs?.takeIf { it > 0 }?.let { builder.setStartTime(it / 1000.0) }
        media.activeTextTrackId?.let { builder.setActiveTrackIds(longArrayOf(it)) }
        return builder.build()
    }

    /** Rebuilds the [CastMedia] of an item loaded by this app (null for foreign or empty media). */
    fun fromMediaInfo(info: MediaInfo, activeTrackIds: LongArray?): CastMedia? {
        val contentUrl = info.contentUrl?.takeIf { it.isNotBlank() }
            ?: info.contentId.takeIf { !it.isNullOrBlank() }
            ?: return null
        val metadata = info.metadata
        val tracks = textTracks(info).mapNotNull { track ->
            val url = track.contentId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CastSubtitle(track.id, url, track.language, track.name)
        }
        val custom = info.customData
        return CastMedia(
            contentUrl = contentUrl,
            contentType = info.contentType?.takeIf { it.isNotBlank() } ?: "video/mp4",
            isLive = info.streamType == MediaInfo.STREAM_TYPE_LIVE,
            title = metadata?.getString(MediaMetadata.KEY_TITLE).orEmpty(),
            subtitle = metadata?.getString(MediaMetadata.KEY_SUBTITLE),
            posterUrl = posterUrl(metadata),
            durationMs = info.streamDuration.takeIf { it > 0 },
            textTracks = tracks,
            activeTextTrackId = tracks.firstOrNull { activeTrackIds?.contains(it.trackId) == true }?.trackId,
            hlsSegmentFormat = custom?.optString(KEY_HLS_SEGMENT_FORMAT)
                ?.let { name -> HlsSegmentFormat.entries.firstOrNull { it.name == name } },
            originalUrl = originalUrl(info) ?: contentUrl,
        )
    }

    /** Original page URL stored in the item's custom data by this app, if any. */
    fun originalUrl(info: MediaInfo?): String? =
        info?.customData?.optString(KEY_ORIGINAL_URL)?.takeIf { it.isNotBlank() }

    fun posterUrl(metadata: MediaMetadata?): String? =
        metadata?.images?.firstOrNull()?.url?.toString()

    fun textTracks(info: MediaInfo?): List<MediaTrack> =
        info?.mediaTracks.orEmpty().filter { it.type == MediaTrack.TYPE_TEXT }

    private fun toMediaTrack(subtitle: CastSubtitle): MediaTrack {
        val builder = MediaTrack.Builder(subtitle.trackId, MediaTrack.TYPE_TEXT)
            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
            .setContentId(subtitle.url)
            .setContentType(CastMediaRules.VTT_CONTENT_TYPE)
        subtitle.language?.takeIf { it.isNotBlank() }?.let { builder.setLanguage(it) }
        subtitle.name?.takeIf { it.isNotBlank() }?.let { builder.setName(it) }
        return builder.build()
    }

    /** Audio segment format hint (the SDK names the audio/muxed format "HLS segment format"). */
    private fun sdkSegmentFormat(format: HlsSegmentFormat): String = when (format) {
        HlsSegmentFormat.TS -> GmsHlsSegmentFormat.TS
        HlsSegmentFormat.FMP4 -> GmsHlsSegmentFormat.FMP4
        HlsSegmentFormat.AAC -> GmsHlsSegmentFormat.AAC
    }

    /** Video segment format hint; none for audio-only (packed AAC) streams. */
    private fun sdkVideoSegmentFormat(format: HlsSegmentFormat): String? = when (format) {
        HlsSegmentFormat.TS -> GmsHlsVideoSegmentFormat.MPEG2_TS
        HlsSegmentFormat.FMP4 -> GmsHlsVideoSegmentFormat.FMP4
        HlsSegmentFormat.AAC -> null
    }
}
