package app.snapcast.core.detection

import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Wire protocol between the bundled detector WebExtension and the app.
 *
 * The extension's top-frame content script opens `browser.runtime.connectNative("snapcast")`
 * and posts JSON objects shaped like [DetectorMessage]. Every message has a "type" discriminator.
 * The app may post [NativeCommand]s back on the same port.
 */

const val NATIVE_APP_NAME: String = "snapcast"
const val DETECTOR_EXTENSION_ID: String = "detector@snapcast.app"
const val DETECTOR_EXTENSION_LOCATION: String = "resource://android/assets/extensions/snapcast_detector/"

@Serializable
sealed interface DetectorMessage

/** Sent by the top-frame content script when it starts (new document). */
@Serializable
@SerialName("hello")
data class HelloMessage(
    val url: String,
    val title: String? = null,
) : DetectorMessage

/** Page metadata update (title / OpenGraph). */
@Serializable
@SerialName("page")
data class PageMessage(
    val url: String,
    val title: String? = null,
    val ogTitle: String? = null,
    val ogImage: String? = null,
) : DetectorMessage

/** One or more media candidates from the network (webRequest) or the DOM. */
@Serializable
@SerialName("media")
data class MediaMessage(
    val items: List<MediaReport>,
) : DetectorMessage

/** The page uses Encrypted Media Extensions (DRM). */
@Serializable
@SerialName("drm")
data class DrmMessage(
    val keySystem: String? = null,
    val frameUrl: String? = null,
) : DetectorMessage

/**
 * A `<video>` of the page started or stopped playing, or its duration or muting changed; a playing one is
 * reported again every 10 s. [element] numbers the elements of the document [frame] names (a random key that,
 * unlike [frameUrl], survives pushState). [src] is its http(s) source; a MediaSource player ([blob]) has none,
 * and its stream is found by frame and [duration]. A message with neither means the element is gone. [at] is
 * when its state last changed.
 */
@Serializable
@SerialName("playback")
data class PlaybackMessage(
    val frameUrl: String? = null,
    val element: Int = 0,
    val src: String? = null,
    val blob: Boolean = false,
    val duration: Double? = null,
    val paused: Boolean = true,
    val muted: Boolean = false,
    val at: Long = 0L,
    val frame: String? = null,
    /** Paused because it played to the end (an ad that finished, rather than an episode someone paused). */
    val ended: Boolean = false,
) : DetectorMessage

@Serializable
data class MediaReport(
    val url: String,
    /** "network" or "dom". */
    val source: String,
    val mime: String? = null,
    /** Total size in bytes (Content-Length, or the total from Content-Range). */
    val size: Long? = null,
    val status: Int? = null,
    /** webRequest resource type: "media", "xmlhttprequest", "main_frame", "sub_frame", "object", "other". */
    val resourceType: String? = null,
    val frameUrl: String? = null,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    /** Request headers as sent by Gecko; keys are case-insensitive (Referer, User-Agent, Origin, Cookie, ...). */
    val requestHeaders: Map<String, String> = emptyMap(),
    /** Cookie header values seen for other hosts in the same tab (segment CDNs), keyed by lower-case host. */
    val cookiesByHost: Map<String, String> = emptyMap(),
    val duration: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val poster: String? = null,
    /** DOM only: a frame of the element as a `data:image/jpeg;base64,` URL, sent after a [ThumbnailsCommand]. */
    val thumbnail: String? = null,
    val title: String? = null,
    /** DOM only: the element's src was a blob:/MediaSource URL (not castable; used to enrich network finds). */
    val isBlob: Boolean = false,
    val tracks: List<TrackReport> = emptyList(),
    val timestamp: Long = 0L,
)

@Serializable
data class TrackReport(
    val url: String,
    /** "subtitles" | "captions" | other <track kind>. */
    val kind: String? = null,
    val lang: String? = null,
    val label: String? = null,
)

/** Commands the app can post to the content script port. */
@Serializable
sealed interface NativeCommand

/** Ask the content script (and its frames, via the background script) to rescan the DOM now. */
@Serializable
@SerialName("rescan")
data object RescanCommand : NativeCommand

/**
 * Ask every frame for one small frame capture of each loaded `<video>` (sent while the video list is open,
 * so the capture only happens when someone looks at it). Answered with media reports carrying [MediaReport.thumbnail].
 * A frame sends each video's picture once per [generation] of the app's list (it changes when the list is reset).
 */
@Serializable
@SerialName("thumbnails")
data class ThumbnailsCommand(val generation: Int) : NativeCommand

object DetectorProtocol {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
        classDiscriminator = "type"
    }

    /** Decodes one message; returns null (never throws) for malformed or unknown messages. */
    fun decode(text: String): DetectorMessage? = try {
        json.decodeFromString(DetectorMessage.serializer(), text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    fun encode(command: NativeCommand): String = json.encodeToString(NativeCommand.serializer(), command)
}
