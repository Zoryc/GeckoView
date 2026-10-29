package app.snapcast.proxy

import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleFormat
import java.io.IOException
import java.io.InputStream

data class ProxyConfig(
    val port: Int = 8642,
    /** Address to bind; 0.0.0.0 so receivers on the LAN can connect. */
    val bindAddress: String = "0.0.0.0",
    /** When [port] is taken, bind an ephemeral port instead of failing. */
    val fallbackToEphemeralPort: Boolean = true,
    /** User-Agent used when a stream was registered without one. */
    val defaultUserAgent: String? = null,
)

/** Remote stream to expose through the proxy. */
data class ProxyStreamRequest(
    val url: String,
    /** Headers replayed upstream for every request of this stream (Referer, User-Agent, Origin, Cookie, ...). */
    val headers: Map<String, String>,
    /** Cookie header per lower-case host for sub-resources on other hosts. */
    val cookiesByHost: Map<String, String> = emptyMap(),
    /** Known type; null lets the proxy decide from Content-Type / URL / body sniffing. */
    val typeHint: StreamType? = null,
    /**
     * The URL the captured Cookie and Authorization were sent to, when it is not [url] (the master playlist when
     * one of its variants is registered): only that URL's host gets them.
     */
    val credentialsUrl: String? = null,
)

/**
 * Why the proxy could not serve a stream: [description] says what the site sent instead (for logs and error
 * messages, never a URL).
 */
data class StreamProblem(val streamId: String, val kind: Kind, val description: String, val atMs: Long) {
    enum class Kind {
        /** The stream's playlist URL answered with something that is not a playlist (a web page, a JSON message,
         * nothing): the link usually expired or the site refused it. */
        NOT_A_PLAYLIST,

        /** The playlist came compressed in a way the proxy cannot decode. */
        UNSUPPORTED_ENCODING,

        /**
         * The proxy could not reach the server at all: its certificate was not trusted by the phone (a missing
         * intermediate the Chromecast's browser fetches itself), its name did not resolve (a filtering DNS on
         * the phone) or the connection was refused. The Chromecast may still reach it directly.
         */
        UNREACHABLE,
    }
}

data class ProxiedStream(
    val id: String,
    /** Path to append to "http://host:port", e.g. "/stream/3f9c.../index.m3u8". */
    val path: String,
)

data class ProxiedSubtitle(
    val id: String,
    /** e.g. "/sub/ab12.vtt" */
    val path: String,
)

/** A local file (e.g. a content:// video) served by the proxy with Range support. */
interface LocalSource {
    val mimeType: String

    /** Total length in bytes, or null when unknown (then Range requests are answered with 200). */
    val length: Long?

    /** Opens the content positioned at [offset]. */
    @Throws(IOException::class)
    fun open(offset: Long): InputStream
}

sealed interface SubtitleSource {
    /** In-memory subtitle text (SRT is converted to WebVTT when served). */
    data class Inline(val text: String, val format: SubtitleFormat) : SubtitleSource

    /** Remote subtitle fetched with [headers]; converted to WebVTT when needed. */
    data class Remote(val url: String, val headers: Map<String, String>, val format: SubtitleFormat) : SubtitleSource
}
