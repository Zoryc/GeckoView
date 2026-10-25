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
    /** Replayed on every request (Referer, Cookie, User-Agent, Origin...). */
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val posterUrl: String? = null,
    /** Null resumes from the saved position when enabled in settings. */
    val startPositionMs: Long? = null,
    val pageUrl: String? = null,
)

/** Starts the full-screen player (supports picture-in-picture). Implemented in the player module. */
interface PlayerLauncher {
    fun play(context: Context, request: PlaybackRequest)
}
