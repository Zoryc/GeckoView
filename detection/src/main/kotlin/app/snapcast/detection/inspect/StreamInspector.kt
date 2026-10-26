package app.snapcast.detection.inspect

import app.snapcast.core.media.DashInspector
import app.snapcast.core.media.HlsParser
import app.snapcast.core.media.HlsPlaylist
import app.snapcast.core.media.MediaClassifier
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.StreamType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Fetches detected streams the way the page did (replaying its headers) to learn what the
 * extension cannot see: HLS variants, renditions, liveness, duration, packaging and DRM keys, DASH
 * representations, and the size of progressive files. Blocking: call it on `Dispatchers.IO`.
 *
 * Redirects are followed here rather than by OkHttp (as the proxy's UpstreamClient does): OkHttp would
 * send the captured Cookie (and Origin, Referer) on to whatever host a redirect names. Every hop gets
 * the headers picked for its own host instead ([InspectionHeaders.forRequest]); Authorization is never
 * sent again once a hop changed host, and no Cookie goes over plain http after an https hop.
 */
@Singleton
internal class StreamInspector @Inject constructor(baseClient: OkHttpClient) {
    private val client: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            // The shared client has none either; the inspector sends exactly the captured cookies, per host.
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }

    /** What could be learned about [video], or null (unreachable, not what it seemed, nothing to learn). Never throws. */
    fun inspect(video: DetectedVideo): InspectionResult? = try {
        when {
            video.type.isAdaptive -> inspectManifest(video)
            video.sizeBytes == null -> inspectSize(video)
            else -> null
        }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /**
     * Whether the top-level document [document] (its URL looks like a stream; its headers are those
     * known for the tab) really is media: a HEAD request, and a ranged GET whose first bytes are sniffed
     * when HEAD fails or names a generic type. Null when it is not (an HTML page whose URL ends in
     * ".webm", an error), or when it could not be told. Never throws.
     */
    fun confirmDirectMedia(document: DetectedVideo): DirectMediaProbe.Media? = try {
        val byHead = try {
            headVerdict(document)
        } catch (e: IOException) {
            DirectMediaProbe.Undecided
        }
        when (byHead) {
            is DirectMediaProbe.Media -> byHead
            DirectMediaProbe.NotMedia -> null
            DirectMediaProbe.Undecided -> sniffVerdict(document) as? DirectMediaProbe.Media
        }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun inspectManifest(video: DetectedVideo): InspectionResult? {
        val body = fetchText(video, video.url) ?: return null
        // The body decides: some servers label DASH manifests as HLS and the other way round.
        return when (MediaClassifier.sniffPlaylist(body.text.take(SNIFF_CHARS)) ?: video.type) {
            StreamType.HLS -> inspectHls(video, body)
            StreamType.DASH -> DashInspector.inspect(body.text, body.finalUrl)?.let(PlaylistInspection::fromDash)
            else -> null
        }
    }

    private fun inspectHls(video: DetectedVideo, body: FetchedText): InspectionResult? =
        when (val playlist = HlsParser.parse(body.text, body.finalUrl)) {
            is HlsPlaylist.Master -> {
                val mediaBody = PlaylistInspection.bestVariantUrl(playlist)?.let { url -> fetchText(video, url) }
                val media = mediaBody?.let { HlsParser.parse(it.text, it.finalUrl) as? HlsPlaylist.Media }
                PlaylistInspection.fromHlsMaster(playlist, media, mediaComplete = mediaBody?.complete ?: true)
            }
            is HlsPlaylist.Media -> PlaylistInspection.fromHlsMedia(playlist, body.complete)
            null -> null
        }

    /** HEAD first; servers that refuse HEAD usually answer a one-byte range request. */
    private fun inspectSize(video: DetectedVideo): InspectionResult? {
        val byHead = try {
            probe(video, video.url, useHead = true)
        } catch (e: IOException) {
            null
        }
        return byHead ?: probe(video, video.url, useHead = false)
    }

    private fun probe(video: DetectedVideo, url: String, useHead: Boolean): InspectionResult? =
        execute(video, url) { if (useHead) it.head() else it.header("Range", "bytes=0-0").get() }.use { response ->
            // The body (if any) is never read: closing the response discards it. A final 3xx is not 2xx: null.
            ProgressiveProbe.fromResponse(
                code = response.code,
                contentLength = response.header("Content-Length"),
                contentRange = response.header("Content-Range"),
                contentType = response.header("Content-Type"),
                contentEncoding = response.header("Content-Encoding"),
            )
        }

    private fun headVerdict(document: DetectedVideo): DirectMediaProbe.Verdict =
        execute(document, document.url) { it.head() }.use { response ->
            DirectMediaProbe.fromHead(response.code, response.header("Content-Type"))
        }

    private fun sniffVerdict(document: DetectedVideo): DirectMediaProbe.Verdict =
        execute(document, document.url) {
            it.header("Range", "bytes=0-${DirectMediaProbe.SNIFF_BYTES - 1}").get()
        }.use { response ->
            val head = if (response.code in 200..299) {
                readPrefix(response.body.byteStream(), DirectMediaProbe.SNIFF_BYTES)
            } else {
                ByteArray(0)
            }
            DirectMediaProbe.fromGet(response.code, response.header("Content-Type"), head)
        }

    private fun fetchText(video: DetectedVideo, url: String): FetchedText? =
        execute(video, url) { it.get() }.use { response ->
            // A final 3xx (too many redirects, a Location that is not http(s)) is not successful either.
            if (!response.isSuccessful) return null
            val read = readLimited(response.body.byteStream(), MAX_BODY_BYTES)
            FetchedText(read.text, response.request.url.toString(), read.complete)
        }

    /**
     * Executes a request for [url] on behalf of [video] ([configure] sets the method and extra headers,
     * the same on every hop), following up to [MAX_REDIRECTS] redirects itself so that each host only
     * gets its own Cookie. The whole chain shares one [CALL_TIMEOUT_SECONDS] deadline. The caller closes
     * the response; it is the last hop's (a 3xx when the chain ends on one).
     */
    private fun execute(video: DetectedVideo, url: String, configure: (Request.Builder) -> Unit): Response {
        var target = url.toHttpUrl() // IllegalArgumentException: caught by the public entry points.
        var crossHost = false
        var sawHttps = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CALL_TIMEOUT_SECONDS)
        repeat(MAX_REDIRECTS + 1) { hop ->
            sawHttps = sawHttps || target.isHttps
            val downgraded = sawHttps && !target.isHttps
            val headers = InspectionHeaders.forRequest(video, target.toString()).filterKeys { name ->
                when {
                    name.equals("Authorization", ignoreCase = true) -> !crossHost && !downgraded
                    name.equals("Cookie", ignoreCase = true) -> !downgraded
                    else -> true
                }
            }
            val builder = requestBuilder(target, headers)
            configure(builder)
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw InterruptedIOException("Inspection timed out")
            val call = client.newCall(builder.build())
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            val response = call.execute()
            val location = response.header("Location")
            // resolve() is null for a Location that is not http(s): that 3xx is then the final response.
            val next = if (response.code in REDIRECT_CODES && location != null && hop < MAX_REDIRECTS) {
                response.request.url.resolve(location)
            } else {
                null
            }
            if (next == null) return response
            response.close()
            if (!next.host.equals(target.host, ignoreCase = true)) crossHost = true
            target = next
        }
        throw IOException("Too many redirects")
    }

    private fun requestBuilder(url: HttpUrl, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder().url(url)
        for ((name, value) in headers) {
            try {
                builder.header(name, value)
            } catch (e: IllegalArgumentException) {
                // OkHttp rejects non-ASCII header values; the request still has a chance without it.
            }
        }
        return builder
    }

    /** A playlist body; [finalUrl] is the URL after redirects, the base for relative URIs. */
    private class FetchedText(val text: String, val finalUrl: String, val complete: Boolean)

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val CALL_TIMEOUT_SECONDS = 25L
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val SNIFF_CHARS = 1024
        const val MAX_REDIRECTS = 10
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/** Text of at most [limit] bytes read from [input] (UTF-8); [complete] is false when the limit cut it. */
internal data class LimitedText(val text: String, val complete: Boolean)

internal fun readLimited(input: InputStream, limit: Int): LimitedText {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    var complete = false
    while (out.size() < limit) {
        val read = input.read(chunk, 0, minOf(chunk.size, limit - out.size()))
        if (read < 0) {
            complete = true
            break
        }
        out.write(chunk, 0, read)
    }
    if (!complete) complete = input.read() < 0
    return LimitedText(String(out.toByteArray(), Charsets.UTF_8), complete)
}

/** At most the first [limit] bytes of [input] (fewer when it ends sooner). */
internal fun readPrefix(input: InputStream, limit: Int): ByteArray {
    val out = ByteArray(limit)
    var size = 0
    while (size < limit) {
        val read = input.read(out, size, limit - size)
        if (read < 0) break
        size += read
    }
    return out.copyOf(size)
}
