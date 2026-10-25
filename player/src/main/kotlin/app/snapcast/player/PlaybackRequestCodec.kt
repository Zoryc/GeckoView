package app.snapcast.player

import kotlinx.serialization.json.Json

/** JSON form of a [PlaybackRequest] carried in the player activity's launch intent. */
internal object PlaybackRequestCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(request: PlaybackRequest): String = json.encodeToString(PlaybackRequest.serializer(), request)

    /** The decoded request, or null when [raw] is missing, malformed or has no URL. */
    fun decode(raw: String?): PlaybackRequest? {
        if (raw.isNullOrBlank()) return null
        val request = try {
            json.decodeFromString(PlaybackRequest.serializer(), raw)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            return null
        }
        return request.takeIf { it.url.isNotBlank() }
    }
}
