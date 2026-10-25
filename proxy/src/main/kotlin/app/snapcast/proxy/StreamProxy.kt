package app.snapcast.proxy

import okhttp3.OkHttpClient

/**
 * Local HTTP server the receiver streams from. Forwards requests to the real URLs with the captured
 * headers, supports Range/HEAD/OPTIONS, streams bodies without buffering whole files, rewrites HLS
 * playlists and DASH manifests so every sub-resource also goes through the proxy, and adds CORS headers.
 *
 * Routes:
 * - `GET|HEAD /stream/{id}[/{name}]`            main resource of a registered stream
 * - `GET|HEAD /u/{id}/{sig}/{b64url}[/{name}]`  exact sub-resource URL (HLS variants, segments, keys)
 * - `GET|HEAD /b/{id}/{sig}/{b64base}/{rest}`   base-relative sub-resource (DASH templates/BaseURL)
 * - `GET|HEAD /local/{id}[/{name}]`             registered [LocalSource]
 * - `GET|HEAD /sub/{id}.vtt`                    registered subtitle, always served as WebVTT
 * - `OPTIONS *`                                 CORS preflight
 * - `GET /health`                               liveness check
 *
 * `{sig}` is an HMAC over the stream id and the encoded URL with a per-process secret, so the proxy
 * cannot be used as an open relay for the user's cookies.
 *
 * Thread-safe. [start]/[stop] may be called from any thread.
 */
class StreamProxy(
    private val client: OkHttpClient,
    private val config: ProxyConfig = ProxyConfig(),
    /** HMAC key; random per instance when null. */
    secret: ByteArray? = null,
    private val log: (String) -> Unit = {},
) {
    val isRunning: Boolean get() = TODO()

    /** Bound port while running, otherwise -1. */
    val port: Int get() = TODO()

    /** Starts the server (no-op when already running). Throws IOException when the port cannot be bound. */
    fun start(): Unit = TODO()

    fun stop(): Unit = TODO()

    fun registerStream(request: ProxyStreamRequest): ProxiedStream = TODO()

    fun registerLocal(source: LocalSource, fileNameHint: String): ProxiedStream = TODO()

    fun registerSubtitle(source: SubtitleSource): ProxiedSubtitle = TODO()

    fun unregister(id: String): Unit = TODO()

    /** Removes every registration. */
    fun clear(): Unit = TODO()

    /** "http://[host]:[port][path]" (IPv6 hosts are bracketed). */
    fun url(host: String, path: String): String = TODO()
}
