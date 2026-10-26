package app.snapcast.player.media

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Network interceptor that sets the Cookie header of every request (each redirect hop included) for that
 * request's host only, the rule the cast proxy's HeaderPolicy follows: [originCookie] (captured for the played
 * URL) goes only to the host of [originUrl], and each of [cookiesByHost] only to its own host. Any other
 * Cookie header is removed, so the page's cookies never reach third-party segment, subtitle or redirect hosts.
 *
 * OkHttp builds a redirect's follow-up from the request as it was before the network interceptors ran, so the
 * Cookie set here is never carried over to the next host; this interceptor picks the right one again.
 */
internal class ScopedCookieInterceptor(
    originUrl: String,
    private val originCookie: String?,
    cookiesByHost: Map<String, String>,
) : Interceptor {
    private val originHost: String? = originUrl.toHttpUrlOrNull()?.host?.let { RequestHeaders.normalizeHost(it) }
    private val cookiesByHost: Map<String, String> = RequestHeaders.cookiesByHost(cookiesByHost)

    /** Cookie header for a request to [host], or null when none may be sent there. */
    fun cookieFor(host: String): String? {
        val normalized = RequestHeaders.normalizeHost(host)
        return if (originHost != null && normalized == originHost) {
            originCookie ?: cookiesByHost[normalized]
        } else {
            cookiesByHost[normalized]
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cookie = cookieFor(request.url.host)
        if (cookie == null && request.header(COOKIE) == null) return chain.proceed(request)
        val builder = request.newBuilder().removeHeader(COOKIE)
        if (cookie != null) builder.header(COOKIE, cookie)
        return chain.proceed(builder.build())
    }

    private companion object {
        const val COOKIE = "Cookie"
    }
}
