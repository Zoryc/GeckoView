package app.snapcast.player

import android.content.Context
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleTrack
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
)

/** Starts the full-screen player (supports picture-in-picture). Implemented in the player module. */
interface PlayerLauncher {
    fun play(context: Context, request: PlaybackRequest)
}
