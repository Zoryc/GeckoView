package app.snapcast.player

import android.content.Context
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Everything the built-in Media3 player needs to play a detected video on the phone. */
@Serializable
data class PlaybackRequest(
    val url: String,
    val title: String,
    val type: StreamType,
    val mimeType: String? = null,
    /**
     * Captured request headers (Referer, User-Agent, Origin, Cookie...). All of them are sent with every
     * http(s) request of the stream except the Cookie, which goes only to the host of [url] (as in the cast
     * proxy). OkHttp drops Authorization after a redirect to another host.
     */
    val headers: Map<String, String> = emptyMap(),
    /**
     * Cookie header per extra host (segment CDNs, subtitle hosts), keyed by host. Each one goes only to its
     * own host; the Cookie in [headers] goes only to the host of [url].
     */
    val cookiesByHost: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val posterUrl: String? = null,
    /** Null resumes from the saved position when enabled in settings. */
    val startPositionMs: Long? = null,
    val pageUrl: String? = null,
    /** From a private tab: the position is never written to the resume store. */
    val isPrivate: Boolean = false,
    /** The quality picked in "Choose quality"; null lets the player adapt to the connection and screen. */
    val quality: PlaybackQuality? = null,
)

/** One quality of an adaptive stream, as described by its playlist (any field may be unknown). */
@Serializable
data class PlaybackQuality(
    val width: Int? = null,
    val height: Int? = null,
    val bandwidth: Long? = null,
)

/** Starts the full-screen player (supports picture-in-picture). Implemented in the player module. */
interface PlayerLauncher {
    fun play(context: Context, request: PlaybackRequest)

    /**
     * Playbacks the server refused (the link expired) that the user asked to retry after reloading the page
     * they came from ([PlaybackRequest.pageUrl]). The browser reloads it and plays the same video again.
     */
    val reloadRequests: Flow<PlaybackRequest>
}
