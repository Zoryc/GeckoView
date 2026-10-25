package app.snapcast.player.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.player.PlaybackRequest
import okhttp3.OkHttpClient

/** Builds the ExoPlayer and the [MediaItem] for one [PlaybackRequest]. */
internal class PlayerFactory(private val okHttpClient: OkHttpClient) {

    /**
     * A player whose http(s) requests replay the request's captured headers (through the shared OkHttp
     * client) while content:// and file:// URIs, e.g. user-picked subtitle files, go through
     * [DefaultDataSource]'s local data sources.
     */
    @OptIn(UnstableApi::class)
    fun createPlayer(context: Context, request: PlaybackRequest): ExoPlayer {
        val headers = RequestHeaders.from(request.headers)
        val httpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(headers.userAgent)
            .setDefaultRequestProperties(headers.properties)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
    }

    fun createMediaItem(request: PlaybackRequest): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(request.title)
            .setArtworkUri(request.posterUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .build()
        return MediaItem.Builder()
            .setUri(request.url)
            .setMediaId(request.url)
            .setMimeType(PlayerMimeTypes.forStream(request.type, request.mimeType))
            .setSubtitleConfigurations(subtitleConfigurations(request.subtitles))
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * One configuration per distinct subtitle URL. A subtitle file the user picked themselves is selected
     * by default; detected page tracks stay off until chosen in the player's subtitle menu.
     */
    private fun subtitleConfigurations(tracks: List<SubtitleTrack>): List<MediaItem.SubtitleConfiguration> {
        val usable = tracks.filter { it.url.isNotBlank() }.distinctBy { it.url }
        val defaultTrack = usable.firstOrNull { it.isLocal }
        return usable.map { track ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                .setId(track.id)
                .setMimeType(PlayerMimeTypes.forSubtitle(track))
                .setLanguage(track.language)
                .setLabel(track.label ?: track.language)
                .setSelectionFlags(if (track === defaultTrack) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
    }

    private companion object {
        const val SEEK_INCREMENT_MS = 10_000L
    }
}
