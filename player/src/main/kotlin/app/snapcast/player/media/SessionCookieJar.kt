package app.snapcast.player.media

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Cookies the servers set while one video plays (a CDN session cookie on the playlist or key response, for
 * example), sent back where they apply as the browser would. In memory only, gone with the player.
 */
internal class SessionCookieJar : CookieJar {
    private val cookies = ArrayList<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (cookie in cookies) {
            val index = this.cookies.indexOfFirst { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            when {
                cookie.expiresAt <= now -> if (index >= 0) this.cookies.removeAt(index)
                // A replaced cookie keeps its place (its creation time, for the order below).
                index >= 0 -> this.cookies[index] = cookie
                else -> this.cookies += cookie
            }
        }
        while (this.cookies.size > MAX_COOKIES) this.cookies.removeAt(0)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        // Browser order (RFC 6265 5.4: longer paths first, then older), so a name's first value is the one servers read.
        return cookies.filter { it.expiresAt > now && it.matches(url) }.sortedByDescending { it.path.length }
    }

    private companion object {
        const val MAX_COOKIES = 50
    }
}
