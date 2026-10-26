package app.snapcast.player.media

import app.snapcast.core.util.CookieHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Network interceptor that sets the credentials of every request (each redirect hop included) for that request's
 * host only, the rules the cast proxy's HeaderPolicy follows:
 * - [originCookie] (captured for the played URL) and [authorization] go only to the host of [originUrl], and each of
 *   [cookiesByHost] only to its own host; per cookie name the per-host value (seen later in the tab) wins;
 * - cookies the servers set during this playback (OkHttp's [SessionCookieJar] puts them on the request before this
 *   interceptor runs) are added where they apply and win over captured ones, as in the browser.
 * Any other Cookie or Authorization header is removed, so the page's credentials never reach third-party segment,
 * subtitle or redirect hosts.
 *
 * OkHttp builds a redirect's follow-up from the request as it was before the network interceptors ran, so the
 * headers set here are never carried over to the next host; this interceptor picks the right ones again.
 */
internal class ScopedCookieInterceptor(
    originUrl: String,
    private val originCookie: String?,
    cookiesByHost: Map<String, String>,
    private val authorization: String? = null,
) : Interceptor {
    private val originHost: String? = originUrl.toHttpUrlOrNull()?.host?.let { RequestHeaders.normalizeHost(it) }
    private val cookiesByHost: Map<String, String> = RequestHeaders.cookiesByHost(cookiesByHost)

    /** Captured Cookie header for a request to [host], or null when none may be sent there. */
    fun cookieFor(host: String): String? {
        val normalized = RequestHeaders.normalizeHost(host)
        return if (originHost != null && normalized == originHost) {
            CookieHeaders.merge(originCookie, cookiesByHost[normalized])
        } else {
            cookiesByHost[normalized]
        }
    }

    /** Authorization header for a request to [host]: the captured one on the played URL's host only. */
    fun authorizationFor(host: String): String? =
        authorization?.takeIf { originHost != null && RequestHeaders.normalizeHost(host) == originHost }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        // What is on the request now came from the session cookie jar (cookies set during this playback).
        val cookie = CookieHeaders.merge(cookieFor(host), request.header(COOKIE))
        val authorization = authorizationFor(host)
        if (cookie == request.header(COOKIE) && authorization == request.header(AUTHORIZATION)) return chain.proceed(request)
        val builder = request.newBuilder().removeHeader(COOKIE).removeHeader(AUTHORIZATION)
        if (cookie != null) builder.header(COOKIE, cookie)
        if (authorization != null) builder.header(AUTHORIZATION, authorization)
        return chain.proceed(builder.build())
    }

    private companion object {
        const val COOKIE = "Cookie"
        const val AUTHORIZATION = "Authorization"
    }
}
