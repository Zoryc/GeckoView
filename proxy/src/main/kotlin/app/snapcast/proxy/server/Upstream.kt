package app.snapcast.proxy.server

import app.snapcast.core.util.CookieHeaders
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Headers replayed upstream for one registration. The captured headers (Referer, User-Agent, Origin, extras)
 * go to every host; the Cookie header only to the original host, and per-host cookies only to their host.
 * Authorization, like Cookie, goes only to the original host (and never after a redirect to another host), so a
 * playlist naming segments, keys or ads on third-party hosts does not hand them the site's credentials.
 *
 * Cookies the upstream sets while this registration is used (Set-Cookie on a playlist, key or redirect, e.g. a
 * CDN session token) are kept in memory and sent back where they apply, as the browser would: the received ones
 * win over the captured request cookie and the tab's per-host cookie, and among them the one with the longest
 * path, then the oldest (RFC 6265 5.4).
 */
internal class HeaderPolicy(
    headers: Map<String, String>,
    originalUrl: String,
    cookiesByHost: Map<String, String>,
    defaultUserAgent: String?,
) {
    private val replayed: List<Pair<String, String>>
    private val originHost: String? = originalUrl.toHttpUrlOrNull()?.host?.let(::normalizeHost)
    private val cookie: String?
    private val authorization: String?
    private val cookiesByHost: Map<String, String> = cookiesByHost
        .filterValues { it.isNotBlank() }
        .mapKeys { normalizeHost(it.key) }

    init {
        var userAgent: String? = null
        var capturedCookie: String? = null
        var capturedAuthorization: String? = null
        val kept = ArrayList<Pair<String, String>>()
        for ((name, value) in headers) {
            val lower = name.trim().lowercase(Locale.ROOT)
            when {
                value.isBlank() -> Unit
                lower == "cookie" -> capturedCookie = value
                lower == "authorization" -> capturedAuthorization = value
                lower == "user-agent" -> userAgent = value
                lower in NOT_REPLAYED -> Unit
                else -> kept += name.trim() to value
            }
        }
        (userAgent ?: defaultUserAgent?.takeIf { it.isNotBlank() })?.let { kept += "User-Agent" to it }
        replayed = kept
        cookie = capturedCookie
        authorization = capturedAuthorization
    }

    /**
     * Received cookies, least recently set first, each with its creation order (kept when it is set again);
     * guarded by itself, never written to disk and gone with the registration.
     */
    private val sessionCookies = ArrayList<ReceivedCookie>()
    private var nextCreated = 0L

    /** Captured Cookie header for a request to [host], or null when none may be sent there. */
    fun cookieFor(host: String): String? {
        val normalized = normalizeHost(host)
        return if (normalized == originHost) CookieHeaders.merge(cookie, cookiesByHost[normalized]) else cookiesByHost[normalized]
    }

    /** Cookie header for a request to [url]: the captured cookies for its host plus the received ones that match. */
    fun cookieFor(url: HttpUrl): String? = CookieHeaders.merge(cookieFor(url.host), receivedCookies(url))

    /** Keeps the cookies an upstream response to [url] set (expired ones remove earlier values). */
    fun rememberCookies(url: HttpUrl, headers: Headers) {
        val received = Cookie.parseAll(url, headers)
        if (received.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(sessionCookies) {
            sessionCookies.removeAll { it.cookie.expiresAt <= now }
            for (cookie in received) {
                val index = sessionCookies.indexOfFirst { it.cookie.name == cookie.name && it.cookie.domain == cookie.domain && it.cookie.path == cookie.path }
                val created = if (index >= 0) sessionCookies.removeAt(index).created else nextCreated++
                if (cookie.expiresAt > now) sessionCookies += ReceivedCookie(cookie, created)
            }
            // Past the limit the least recently set cookie goes, as in a browser (RFC 6265 5.3).
            while (sessionCookies.size > MAX_SESSION_COOKIES) sessionCookies.removeAt(0)
        }
    }

    private fun receivedCookies(url: HttpUrl): String? {
        val now = System.currentTimeMillis()
        val matching = synchronized(sessionCookies) { sessionCookies.filter { it.cookie.expiresAt > now && it.cookie.matches(url) } }
        // Browser order (RFC 6265 5.4: longer paths first, then older), so a name's first value is the one servers read.
        return matching.sortedWith(BROWSER_ORDER).joinToString("; ") { "${it.cookie.name}=${it.cookie.value}" }.takeIf { it.isNotEmpty() }
    }

    private class ReceivedCookie(val cookie: Cookie, val created: Long)

    /**
     * Request headers for [url]. Authorization is only sent to the original host; [dropAuthorization] is set
     * after a redirect to another host (even one leading back to the original host), mirroring what browsers and
     * OkHttp do with credentials.
     */
    fun headersFor(url: HttpUrl, dropAuthorization: Boolean): Headers {
        val builder = Headers.Builder()
        for ((name, value) in replayed) addLenient(builder, name, value)
        if (!dropAuthorization && authorization != null && normalizeHost(url.host) == originHost) {
            addLenient(builder, "Authorization", authorization)
        }
        cookieFor(url)?.let { addLenient(builder, "Cookie", it) }
        return builder.build()
    }

    private fun addLenient(builder: Headers.Builder, name: String, value: String) {
        try {
            // Non-ASCII values are sent as-is (as browsers do); line breaks could inject headers and are flattened.
            builder.addUnsafeNonAscii(name, value.replace('\r', ' ').replace('\n', ' '))
        } catch (e: IllegalArgumentException) {
            // A header name with illegal characters cannot be sent; skip it rather than failing the request.
        }
    }

    companion object {
        private const val MAX_SESSION_COOKIES = 50
        private val BROWSER_ORDER = compareByDescending<ReceivedCookie> { it.cookie.path.length }.thenBy { it.created }

        /** Hop-by-hop, framing, conditional and content-negotiation headers the proxy controls itself. */
        private val NOT_REPLAYED = setOf(
            "host", "connection", "keep-alive", "proxy-connection", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "content-length", "content-type", "expect", "range", "if-range",
            "if-match", "if-none-match", "if-modified-since", "if-unmodified-since", "accept-encoding",
        )

        fun normalizeHost(host: String): String = host.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]").trimEnd('.')
    }
}

/** An upstream response after redirects; closing it releases the connection and forgets the call. */
internal class UpstreamResponse(val response: Response, private val onClose: () -> Unit) : Closeable {
    val code: Int get() = response.code

    /** URL of the final request (after redirects): the base for relative references in playlists. */
    val finalUrl: HttpUrl get() = response.request.url

    fun header(name: String): String? = response.header(name)

    override fun close() {
        try {
            response.close()
        } finally {
            onClose()
        }
    }
}

/**
 * Executes upstream requests with OkHttp. Redirects are followed here rather than by OkHttp so the Cookie header
 * is re-evaluated for every host, and every response's Set-Cookie is handed to the registration's [HeaderPolicy]
 * (OkHttp's own cookie jar and cache stay disabled: the proxy never stores media or cookies on disk). In-flight
 * calls are tracked so [cancelAll] can abort them.
 */
internal class UpstreamClient(client: OkHttpClient) {
    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .build()
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    /**
     * @param range value of the Range header to send, or null.
     * @param identity request an unencoded body (`Accept-Encoding: identity`); otherwise OkHttp negotiates gzip and
     *   decodes it transparently, which is only acceptable for bodies the proxy reads completely.
     */
    @Throws(IOException::class)
    fun execute(url: HttpUrl, method: String, policy: HeaderPolicy, range: String?, identity: Boolean): UpstreamResponse {
        var target = url
        var crossHost = false
        repeat(MAX_REDIRECTS + 1) { hop ->
            val builder = Request.Builder()
                .url(target)
                .headers(policy.headersFor(target, dropAuthorization = crossHost))
            range?.let { builder.header("Range", it) }
            if (identity) builder.header("Accept-Encoding", "identity")
            if (method == "HEAD") builder.head() else builder.get()
            val call = http.newCall(builder.build())
            calls += call
            val response = try {
                call.execute()
            } catch (e: Throwable) {
                calls -= call
                throw e
            }
            policy.rememberCookies(response.request.url, response.headers)
            val location = response.header("Location")
            val next = if (response.code in REDIRECT_CODES && location != null && hop < MAX_REDIRECTS) {
                response.request.url.resolve(location)
            } else {
                null
            }
            if (next == null) return UpstreamResponse(response) { calls -= call }
            response.close()
            calls -= call
            if (!next.host.equals(target.host, ignoreCase = true)) crossHost = true
            target = next
        }
        throw IOException("Too many redirects")
    }

    /** Cancels every in-flight upstream call (their streams fail and are closed by their readers). */
    fun cancelAll() {
        calls.toList().forEach { it.cancel() }
        calls.clear()
    }

    private companion object {
        const val MAX_REDIRECTS = 10
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
