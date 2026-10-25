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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches detected streams the way the page did (replaying its headers) to learn what the
 * extension cannot see: HLS variants, renditions, liveness, duration, packaging and DRM keys, DASH
 * representations, and the size of progressive files. Blocking: call it on `Dispatchers.IO`.
 */
@Singleton
internal class StreamInspector @Inject constructor(baseClient: OkHttpClient) {
    private val client: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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

    private fun inspectManifest(video: DetectedVideo): InspectionResult? {
        val body = fetchText(video.url, InspectionHeaders.forRequest(video)) ?: return null
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
                val mediaBody = PlaylistInspection.bestVariantUrl(playlist)?.let { url ->
                    fetchText(url, InspectionHeaders.forRequest(video, url))
                }
                val media = mediaBody?.let { HlsParser.parse(it.text, it.finalUrl) as? HlsPlaylist.Media }
                PlaylistInspection.fromHlsMaster(playlist, media, mediaComplete = mediaBody?.complete ?: true)
            }
            is HlsPlaylist.Media -> PlaylistInspection.fromHlsMedia(playlist, body.complete)
            null -> null
        }

    /** HEAD first; servers that refuse HEAD usually answer a one-byte range request. */
    private fun inspectSize(video: DetectedVideo): InspectionResult? {
        val headers = InspectionHeaders.forRequest(video)
        val byHead = try {
            probe(video.url, headers, useHead = true)
        } catch (e: IOException) {
            null
        }
        return byHead ?: probe(video.url, headers, useHead = false)
    }

    private fun probe(url: String, headers: Map<String, String>, useHead: Boolean): InspectionResult? {
        val builder = requestBuilder(url, headers)
        if (useHead) builder.head() else builder.header("Range", "bytes=0-0").get()
        client.newCall(builder.build()).execute().use { response ->
            // The body (if any) is never read: closing the response discards it.
            return ProgressiveProbe.fromResponse(
                code = response.code,
                contentLength = response.header("Content-Length"),
                contentRange = response.header("Content-Range"),
                contentType = response.header("Content-Type"),
                contentEncoding = response.header("Content-Encoding"),
            )
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>): FetchedText? {
        client.newCall(requestBuilder(url, headers).get().build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val read = readLimited(response.body.byteStream(), MAX_BODY_BYTES)
            return FetchedText(read.text, response.request.url.toString(), read.complete)
        }
    }

    private fun requestBuilder(url: String, headers: Map<String, String>): Request.Builder {
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
